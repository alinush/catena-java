package org.catena.client;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.Vector;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import javax.annotation.concurrent.GuardedBy;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.ScriptException;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.utils.ListenerRegistration;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.KeyChainGroup;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.WalletProtobufSerializer.WalletFactory;
import org.bitcoinj.wallet.WalletTransaction.Pool;
import org.catena.common.CatenaStatement;
import org.catena.common.CatenaUtils;
import org.catena.common.CatenaWalletExtension;
import org.catena.common.SimpleWallet;
import org.catena.common.TxUtils;
import org.catena.common.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Iterators;

public class ClientWallet extends SimpleWallet {
    private static final Logger log = LoggerFactory.getLogger(ClientWallet.class);
    
    /**
     * Listeners of CatenaClient events will be executed on this thread. 
     */
    private Executor executor = Threading.USER_THREAD;

    /**
     * Listeners for users of CatenaClient to be notified about new statements, withdrawn statements, reorganization of 
     * blockchain and whistleblowing events.
     */
    private final CopyOnWriteArrayList<ListenerRegistration<CatenaStatementListener>> stmtListeners
        = new CopyOnWriteArrayList<ListenerRegistration<CatenaStatementListener>>();
    private final CopyOnWriteArrayList<ListenerRegistration<CatenaReorganizeListener>> reorgListeners
        = new CopyOnWriteArrayList<ListenerRegistration<CatenaReorganizeListener>>();
    private CopyOnWriteArrayList<ListenerRegistration<CatenaWhistleblowListener>> whistleblowListeners
        = new CopyOnWriteArrayList<ListenerRegistration<CatenaWhistleblowListener>>();
    
    /**
     * The chain of confirmed Catena statements (i.e., their TXNs are in BUILDING status), aka the building queue (BQ).
     */
    @GuardedBy("lock") protected Stack<CatenaStatement> bq = new Stack<CatenaStatement>();
    
    /**
     * A pending queue (PQ) of unconfirmed Catena statements (i.e., their TXNS are in PENDING status). We use this queue
     * to prevent lies after forks: if there's a fork that withdrawns some of our previous statements, we want to make sure
     * that the new fork will commit the same statements, so we remember these statements in the PQ. We need to do this
     * because it might be a while until the statements appear in the new fork. Then, whenever a new confirmed statement
     * arrives, we make sure it commits the same data as in the pending queue, and if so we move it from the PQ to the BQ.
     * 
     * We only keep the statement data here and not the TXNs associated with that data, because while the TXN is allowed
     * to change, the statement data is not. One invariant that we maintain is that every statement in PQ came from a TX
     * that chained correctly to the TXNs in BQ (i.e., signature verified).
     */
    @GuardedBy("lock") protected Deque<CatenaStatement> pq = new LinkedList<CatenaStatement>();
    
    /**
     * Set to true after we receive and process the root-of-trust TXN. This is
     * set both when a new wallet is created and when rebooting an old wallet.
     */
    boolean processedRootOfTrustTxn = false;

    /**
     * Set as a hint by the creator of the ClientWallet object.  
     */
    private boolean isRebootingHint;
    
    
    public ClientWallet(NetworkParameters params) {
        super(params);
        log.trace("Initializing Catena ClientWallet (params)...");
    }
    
    public ClientWallet(NetworkParameters params, KeyChainGroup keyChainGroup) {
        super(params, keyChainGroup);
        
        log.trace("Initializing Catena ClientWallet (params, kcg)...");
    }
    
    /**
     * Factory for this wallet type, used in WalletAppKit subclasses (e.g., in CatenaClient)
     */
    public static class Factory implements WalletFactory {
        
        @Override
        public ClientWallet create(NetworkParameters params, KeyChainGroup keyChainGroup) {
            // Need our own custom Wallet subclass in CatenaService
            return new ClientWallet(params, keyChainGroup);
        }
    }
    
    /**
     * Helper method for casting a wallet to a ClientWallet.
     * 
     * @param w
     * @return
     */
    public static ClientWallet castWallet(Wallet w) {
        checkState(w instanceof ClientWallet);
        
        return (ClientWallet)w;
    }
    
    /**
     * The client will never process the root-of-trust TX because it does not
     * have its PK added to the wallet, so the TX will seem irrelevant to the
     * wallet. To prevent that, we override this method.
     * 
     * This method also adds the PK in the root-of-trust TX to the list of
     * watched addresses.
     */
    @Override
    public boolean isTransactionRelevant(Transaction tx) throws ScriptException {
        lock.lock();
        try {
            CatenaWalletExtension ext = getCatenaExtension();
            boolean isRelevant = super.isTransactionRelevant(tx);
            
            boolean isRootOfTrustTx = false;
            if(ext.hasRootOfTrustTxid()) {
                isRootOfTrustTx = ext.getRootOfTrustTxid().equals(tx.getHash());
                
                if(isRootOfTrustTx) {
                    // NOTE: We save this in the wallet extension later, for now we just display them.
                    Address chainAddr = tx.getOutput(0).getAddressFromP2PKHScript(params);
                    log.debug("Identified chain address from root-of-trust TX " +
                            tx.getHashAsString() + ": " + chainAddr);
                    if(ext.hasName() == false) { 
                        log.debug("Also, identified chain name from root-of-trust TX " +
                            tx.getHashAsString() + ": " + 
                            new String(CatenaUtils.getCatenaTxData(tx)));
                    }
                    
                    checkState(chainAddr != null, "No P2PKH address in the root-of-trust TX's first output");

                    // NOTE: By the time we add the Catena chain's PK to the wallet it could be too late because bitcoinj
                    // might've downloaded future blocks (i.e., blocks past the root-of-trust block) and ignored Catena 
                    // TXs in those blocks since it didn't have this PK in its Bloom filter yet. For now, this code 
                    // doesn't have any effect until bitcoinj will handle blockchain redownloads. That's why we also pass
                    // the chain address to the CatenaClient constructor.
                    //addWatchedAddresses(ImmutableList.<Address>of(chainAddr), 1);
                }
            } else {
                //log.trace("Too early to match root-of-trust TXN because TXID is not yet set in wallet extension.");
            }
            
            return isRelevant || isRootOfTrustTx;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Returns the address of the chain.
     */
    public Address getChainAddress() {
        List<Address> addrs = getWatchedAddresses();
        checkState(!addrs.isEmpty(), "chain address has not been set in wallet yet");
        checkState(addrs.size() == 1, "expected exactly one chain address in the wallet, got " + addrs.size());
        return addrs.get(0);
    }
    
    /**
     * Clients read chains, they don't write them, so they don't have chain key.
     */
    public ECKey getChainKey() {
        throw new UnsupportedOperationException("Catena clients do not have chain keys, just addresses");
    }
    
    /**
     * Handles the root of trust TXN when received: we make sure it has the expected chain address in it (due to 
     * limitations of bitcoinj, we have to filter by the chainAddr and not the TXID)
     * 
     * @param tx
     * @return false if we haven't yet received the root-of-trust TXN, true if we received and validated it.
     */
    protected boolean processRootOfTrustTxn(Transaction tx) {
        // We don't start updating the Catena chain if the root-of-trust TX processing has failed        
        if(processedRootOfTrustTxn)
            return true;
        
        // See if we can find root-of-trust TXN in the wallet
        CatenaWalletExtension ext = getCatenaExtension();
        
        if(tx == null) {
            //log.debug("Did not yet receive root-of-trust TXN");
            return false;
        }
        
        log.debug("Processing root of trust TX {} ...", tx.getHashAsString());
        
        if(CatenaUtils.maybeCatenaTx(tx) == false) {
            log.error("Root-of-trust TXN is not a Catena TX: {}", tx);
            queueOnWhistleblow(tx, "invalid root-of-trust TXN (not a Catena TX): " + tx);
            return false;
        } 
        
        // NOTE: We do not check the signature on this root-of-trust TX since it wouldn't give us much really.
        // That's why we call this the "root-of-trust" TXN: we trust it to be unique in the blockchain.
        
        String chainName = new String(CatenaUtils.getCatenaTxData(tx));
        Address addr = tx.getOutput(0).getAddressFromP2PKHScript(getParams());
        
        // NOTE: We're gonna get rid of this code in the future if bitcoinj
        // allows us to restart the blockchain download once we obtain the 
        // root-of-trust TX.
        Address expected = getChainAddress();
        log.debug("Root-of-trust TXN chain address {} vs. expected chain addr {}", addr, expected);
        if(addr.equals(expected) == false) {
            log.error("Root-of-trust TXN's PK '{}' was not the expected PK '{}': {}", addr, expected, tx);
            queueOnWhistleblow(tx, "The Catena chain's PK from the root-of-trust TX '" + addr + 
                    "' is different than the provided one '" + expected + "'");
            return false;
        }

        log.debug("Saved the chain name in the wallet extension: " + chainName);
        // We might need to set the chain's name if the wallet rebooted but has already processed the root-of-trust TXN in 
        // the past.
        if(ext.hasName() == false) { 
            ext.setName(chainName);
        }

        // Trigger a wallet save
        saveNow();
        
        processedRootOfTrustTxn = true;
        
        return true;
    }
    
    /**
     * Updates our building and pending queues with the latest TXNs from the Wallet. Calls our statement listeners for
     * newly appended statements and withdrawn ones too. Calls the whistleblow listeners if something goes bad (lies,
     * bad signatures).
     * 
     * NOTE: Current implementation will not deliver onWithdrawn/onAppended notifications if the client restarts in the 
     * middle of this call because we lose some state that we don't persist on disk.
     * 
     * NOTE: The invariant here is that the root-of-trust TX is in the wallet but it could be either PENDING or BUILDING.
     * 
     * @param callListeners
     */
    void updateCatenaLog(boolean callListeners) {
        lock.lock();
        
        
        try {
            log.debug("callListeners={}", callListeners);
            
            Sha256Hash rootOfTrustTxid = getCatenaExtension().getRootOfTrustTxid();
            
            Transaction rootOfTrustTxn = getTransaction(rootOfTrustTxid);
            checkNotNull(rootOfTrustTxn);
            checkState(CatenaUtils.maybeCatenaTx(rootOfTrustTxn), "root-of-trust TXN looks like non-Catena TXN");
            
            // INVARIANT: BQ and PQ have to be consistent with one anothe: BQ TXs should be correctly chained and signed.
            // Also, the first statement in PQ should have come from a TXN that was connected to a once-in-BQ TXN. 
            // Also, the first TX in BQ/PQ, if any, should be the root-of-trust TXN. 
            
            Stack<CatenaStatement> withdrawnStack = new Stack<CatenaStatement>();

            // Step 1: Pop BQ
            //
            // If BQ is not empty, pop TXs that are not BUILDING anymore but are still in the building queue (BQ) if
            // fork killed some of our previous BUILDING TXNs, withdrawing their statements. As we pop from BQ, we add 
            // popped TXNs back to PQ to make sure we will not be lied to about the withdrawn statements.
            while(!bq.isEmpty()) {
                CatenaStatement tailStmt = bq.peek();
                
                Transaction tailTxn = getTransaction(tailStmt.getTxHash());
                checkState(CatenaUtils.maybeCatenaTx(tailTxn), "broken invariant: non-Catena TXN in building queue");
                byte[] tailData = tailStmt.getData();
                String tailHexData = Utils.toHex(tailData);
                String tailHash = tailTxn.getHashAsString().substring(0, 7) + "...";
                
                // Check if the TXN was killed by a fork
                if(TxUtils.isBuildingTxn(tailTxn) == false) {
                    log.debug("Pop BQ: Popping statement '" + tailHexData + "' (from tx " + tailHash  + ")");
                    bq.pop();

                    // Keep track of this statement as a withdrawn statement (might be added back to BQ though)
                    withdrawnStack.push(tailStmt);
                    
                    // Move the killed txn from BQ to PQ since it's now either DEAD, IN_CONFLICT or PENDING.
                    log.debug(" -> Push PQ: Pushing statement to PQ '" + tailHexData  + "' (from tx " + tailHash + ")");
                    // We recreate the statement here to get rid of its txid, since it's in PQ now
                    pq.addFirst(CatenaStatement.fromData(tailData));
                } else {
                    // We stop at the first txn that is still in BUILDING status
                    log.debug("Pop BQ: Stopped popping at BUILDING tx " + tailTxn.getHashAsString().substring(0,7) + "...");
                    break;
                }
            }
            
            // INVARIANT: Everything in BQ is BUILDING, but there might be new TXs in the wallet that are BUILDING but 
            // not in BQ yet. Some of these TXNs might be reissued ones for statements that have just been withdrawn
            // or that are in PQ from previous updateCatenaLog calls. 
            
            // Step 2: Push BQ
            //
            // Push reissued and/or newly issued statements into building queue (BQ)
            
            // Step 2.1: Find the next BUILDING txn to push in BQ
            Transaction lastBuildingTxn;
            if(bq.isEmpty()) {              
                // NOTE: We cannot be lied to about the root-of-trust TXN because we know its hash. We just need to make 
                // sure it's in BUILDING status.
                if(TxUtils.isBuildingTxn(rootOfTrustTxn)) {
                    log.debug("Push BQ: Pushing root-of-trust TXN " + rootOfTrustTxid);
                    bq.push(CatenaStatement.fromTxn(rootOfTrustTxn));
                    
                    // If we pushed the root-of-trust TXN in the withdrawn stack, then we pop it here. Note that we do 
                    // not call onWithdrawn on the root-of-trust TXN, since it's not a proper Catena statement.
                    if(!withdrawnStack.isEmpty()) {
                        withdrawnStack.pop();
                    }
                    
                    // If the root-of-trust TX was in PQ (we might have received it as PENDING and then it switched status
                    // to BUILDING), then remove it from PQ.
                    if(!pq.isEmpty()) {
                        log.debug(" -> Pop PQ: Popping root-of-trust TXN from PQ");
                        pq.pollFirst();
                    }
                    
                    lastBuildingTxn = rootOfTrustTxn;
                } else {
                    // Root-of-trust TXN is not BUIDLING yet => no other Catena TXNs can be BUILDING either
                    lastBuildingTxn = null;
                    
                    log.debug("Push BQ: Root-of-trust TXN is not BUILDING yet");
                }
            } else {
                lastBuildingTxn = getTransaction(bq.peek().getTxHash());
                
                checkState(CatenaUtils.maybeCatenaTx(lastBuildingTxn), "broken invariant: non-Catena TXN in building queue");
            }
            
            if(lastBuildingTxn != null)
                log.debug("BQ: Starting to push after TXN " + lastBuildingTxn.getHashAsString().substring(0, 7) + "...");
            else
                log.debug("BQ: No new BUILDING TXNs were found");
            
            // INVARIANT: tailTxn, if non-null, is last txn in BQ AND it has BUILDING status.
            // Also, sizeof(witdrawnStack) >= sizeof(PQ).
            
            
            // Step 2.2: Push txns that connect to the last TXN in BQ, in BQ
            //
            // Now, we add to BQ all remaining BUILDING statements that are not in BQ. We might be adding TXs back to BQ 
            // if they were killed by a fork and removed in Step 1, but new TXNs with the same data were created in the fork.
            if(lastBuildingTxn != null) {
                TransactionOutput prevOutput = lastBuildingTxn.getOutput(0);
                Transaction nextTxn = CatenaUtils.getNextCatenaTx(this, lastBuildingTxn);
                
                while(nextTxn != null) {
                    
                    // Maintain BQ invariant: Check signature and format of TXN before adding to BQ
                    if(CatenaUtils.isSignedCatenaTx(nextTxn, getChainAddress(), prevOutput, true) == false) {
                        log.warn("Push BQ: Whistleblowing! Bad Catena TXN found (tx " + nextTxn.getHash() + ")");
                        queueOnWhistleblow(nextTxn, "ill-formated or incorrectly signed Catena TXN: " + nextTxn.getHash());
                        
                        // We can stop execution here because invariants all hold at this point.
                        return;
                    }
                    
                    if(TxUtils.isBuildingTxn(nextTxn)) {
                        // Add it to BQ (might be adding it back)
                        CatenaStatement tailStmt = CatenaStatement.fromTxn(nextTxn);
                        byte[] tailData = tailStmt.getData();
                        String tailHexData = Utils.toHex(tailData);
                        String tailHash = nextTxn.getHashAsString().substring(0, 7) + "...";
                        
                        bq.push(tailStmt);
                        
                        log.debug("Push BQ: Pushed statement " + tailHexData + " (tx " + tailHash + "...)");
                        
                        // Remove matching statement from PQ, if it's there. Recall that the statement in PQ that is 
                        // being moved to BQ could come from a different TXN (maybe because it's a lie, maybe because 
                        // the TX has a different fee.)
                        if(!pq.isEmpty()) {
                            CatenaStatement headStmt = pq.peekFirst();
                            String headHexData = Utils.toHex(headStmt.getData());
                            
                            log.debug("bqData={}, pqData={}", tailHexData, headHexData);
                            // Check if the BQ tail and the PQ head both commit the same data, or else whistleblow.
                            if(headStmt.hasSameData(tailStmt) == false) {
                                String err = Utils.fmt("expected statement '{}' (hex) but got '{}' (hex) in tx {}", 
                                        headHexData, tailHexData, nextTxn.getHash());
                                
                                log.warn("Push BQ: Whistleblowing! Inconsistent statements detected: " + err);
                                bq.pop();   // pop the lying statement from BQ
                                queueOnWhistleblow(nextTxn, err);
                                break;
                            }
                            
                            // Not a lie, we can move it from BQ to PQ
                            pq.pollFirst();
                            
                            // Check if the statement is being added back after being withdrawn
                            boolean isAddedBack = withdrawnStack.isEmpty() == false; 
                            if(isAddedBack) {
                                CatenaStatement s = withdrawnStack.pop();
                                
                                // Already checked for equivocation above, so this should hold
                                checkState(s.hasSameData(tailStmt), "broken invariant: withdrawn statement does not match reissued one");
                                
                                log.debug(" -> Added back statement " + Utils.toHex(s.getData()));
                            } else {
                                // Call onStatementAppended because this is a new statement, not a reissued one!
                                if(callListeners)
                                    queueOnAppend(tailStmt);
                            }
                        } else { 
                            if(callListeners)
                                queueOnAppend(tailStmt);
                        }
                    } else {
                        // We found a non-building TXN, we are done.
                        break;
                    }
                    
                    // Move on to the next BUILDING txn in the Catena chain, if any
                    prevOutput = nextTxn.getOutput(0); 
                    nextTxn = CatenaUtils.getNextCatenaTx(this, nextTxn);
                }
            }
            
            // INVARIANT: All BUILDING Catena TXNs are in BQ 
            
            // Step 3: Check for lies w.r.t. to the building Catena TXs: we want to catch PENDING/DEAD txns which have been
            // killed because they double spend.
            //
            // NOTE: This is an ~O(n) kind of ordeal, but since bitcoinj does the same thing everytime it receives a TXN
            // (i.e. in Wallet::isTransactionRelevant, it calls findDoubleSpendsAgainst all txns), we don't care too much.
            
            // TODO: REFUND: Will have to slightly adjust the code here to deal with the fact that Catena TXs might have
            // multiple inputs, for additional miner fees.
            
            // We findDoubleSpendsAgainst(building, Pool.DEAD) and get a map of outpoints to txs double spending them
            // Then, we iterate through the double spends and ensure they commit the same data.
            Map<TransactionOutPoint, List<Transaction>> deadDoubleSpends = TxUtils.findDoubleSpendsAgainst(
                    getCatenaBuildingTxns(),
                    getTransactionPool(Pool.DEAD));
            
            for(Map.Entry<TransactionOutPoint, List<Transaction>> e : deadDoubleSpends.entrySet()) {
                TransactionOutPoint outp = e.getKey(); 
                checkState(outp.getIndex() == 0, "broken invariant: Catena TX in BQ was supposed to only have one input");
                
                Iterator<Transaction> txit = e.getValue().iterator();
                checkState(txit.hasNext(), "broken invariant: findDoubleSpendsAgainst returned non-double-spends");
                
                // The 1st returned TXN is from the set of Catena building TXNs
                Transaction tx = txit.next();
                byte[] origData = CatenaUtils.getCatenaTxData(tx);
                
                log.warn("Outpoint " + outp + " was double spent");
                log.warn(" -> 1st Building Catena TXN, txid={}..., stmt={}", tx.getHashAsString().substring(0, 7),
                        Utils.toHex(origData)); 
                
                // The subsequently returned TXNs are DEAD txns that double spend the Catena building TXN
                while(txit.hasNext()) {
                    Transaction ds = txit.next();
                    
                    if(CatenaUtils.maybeCatenaTx(ds)) {
                        byte[] lieData = CatenaUtils.getCatenaTxData(ds);
                        
                        // TODO: the signature verification here can fail because ds is dead and the connected output
                        // we pass here is not connected to ds, but to tx (i.e., the building Catena TX)
                        boolean isCorrectlySigned = CatenaUtils.isSignedCatenaTx(ds, getChainAddress(), 
                                outp.getConnectedOutput(), true);
                        
                        if(CatenaStatement.hasSameData(origData, lieData) == false) {
                            log.warn(" -> 2nd lying TXN, txid={}..., stmt={}, isCorrectlySigned={}", 
                                    ds.getHashAsString().substring(0, 7), Utils.toHex(lieData), isCorrectlySigned);

                            queueOnWhistleblow(ds, Utils.fmt(
                                    "Lie detected w.r.t. Catena txid={}, stmt={}: lying txid={}, stmt={}, isCorrectlySigned={}", 
                                    tx.getHashAsString(), Utils.toHex(origData), ds.getHashAsString(), Utils.toHex(lieData), 
                                    isCorrectlySigned));
                        } else {
                            if(isCorrectlySigned) {
                                log.warn(" -> 2nd consistent, correctly signed TXN, txid={}...", ds.getHashAsString().substring(0, 7));
                            } else {
                                // We will catch the bad signature later if this TX ever becomes BUILDING again,
                                // no need to whistleblow here.
                                log.warn(" -> 2nd consistent, but incorrectly signed TXN, txid={}...",
                                        ds.getHashAsString().substring(0, 7));
                            }
                        }
                    } else {
                        queueOnWhistleblow(ds, "non-Catena TX " + tx.getHashAsString() + " double spent outpoint " + outp);
                    }
                }
            }
            
            // TODO: When you have enough energy, also check for lies among PENDING txns (to speed up detection). 
            //TxUtils.findDoubleSpendsAmongst(wallet().getPendingTransactions()); // here's the first line :)
                        
            // Step 4: Notify listeners about withdrawn statements

            // Call onStatementWithdrawn for statements that were popped in Step 1 and not added back in this Step 2.2
            if(callListeners)
                queueOnWithdrawn(withdrawnStack);
        } finally {
            lock.unlock();
        }
    }
    
    @VisibleForTesting
    public int getNumStatements() {
        lock.lock();
        try {
            if(bq.isEmpty())
                return 0;
            else
                return bq.size() - 1;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Returns an iterator over the confirmed Catena statements in order of issuance if isFwd is true and in reverse
     * otherwise. Does not include the root-of-trust TXN.
     * 
     * @param isFwd
     * @return
     */
    @Override
    @VisibleForTesting
    public Iterator<CatenaStatement> statementIterator(boolean isFwd) {
        
        final Vector<CatenaStatement> bqCopy;

        // WARNING: Do not call wallet() methods here as you could cause a deadlock: updateCatenaLog() has the 
        // wallet lock and needs the bq lock, and this function has the bq lock and would need the wallet() lock.
        //
        // We copy the the statements in BQ, because BQ could get modified while we iterate over it.
        lock.lock();
        try {
            // We do not count the root-of-trust TXN;
            if(bq.size() <= 1)
                return Collections.emptyIterator();
            
            bqCopy = new Vector<CatenaStatement>(bq.size());
            
            // NOTE: We ignore the root-of-trust TXN
            if(isFwd) {
                for(int i = 1; i < bq.size(); i++)
                    bqCopy.add(bq.get(i));
            } else {
                for(int i = bq.size()-1; i >= 1; i--)
                    bqCopy.add(bq.get(i));
            }
        } finally {
            lock.unlock();
        }
        
        return Iterators.unmodifiableIterator(bqCopy.iterator());
    }

    /**
     * Returns an iterator over all the BUILDING Catena TXs (not sure about the order).
     */
    private Iterator<Transaction> getCatenaBuildingTxns() {
        checkState(lock.isHeldByCurrentThread());
        
        Sha256Hash rootOfTrustTxid = getCatenaExtension().getRootOfTrustTxid();
        
        // We do not include root-of-trust TXN, since it cannot be double spent and we might not have the TX it spends
        // in the wallet, which will cause the calling code to fail.
        List<Transaction> buildingTxns = new ArrayList<Transaction>();
        
        boolean skipped = false;
        for(CatenaStatement s : bq) {
            if(!skipped && s.getTxHash().equals(rootOfTrustTxid)) {
                skipped = true;
                continue;
            }
            
            buildingTxns.add(getTransaction(s.getTxHash()));
        }
        
        return buildingTxns.iterator();
    }
    
    /**
     * Adds an onWhistleblow listener that is called when the CatenaServer has equivocated (lied) about a statement.
     * @param listener
     */
    public void addWhistleblowListener(CatenaWhistleblowListener listener) {
        whistleblowListeners.add(new ListenerRegistration<CatenaWhistleblowListener>(listener, executor));
    }

    /**
     * Adds an onStatementAppended listener that is called (in order) for each CatenaStatement appended to the blockchain.
     * 
     * @param listener
     */
    public void addStatementListener(CatenaStatementListener listener) {
        stmtListeners.add(new ListenerRegistration<CatenaStatementListener>(listener, executor));
    }
    
    /**
     * Adds a reorganize listener that can deal with accidental forks in the Bitcoin blockchain and with malicious forks 
     * as well.
     * 
     * @param listener
     */
    public void addReorganizeListener(CatenaReorganizeListener listener) {
        reorgListeners.add(new ListenerRegistration<CatenaReorganizeListener>(listener, executor));
    }
    
    private void queueOnAppend(final CatenaStatement s) {
        for (final ListenerRegistration<CatenaStatementListener> registration : stmtListeners) {
            registration.executor.execute(new Runnable() {
                @Override
                public void run() {
                    registration.listener.onStatementAppended(s);
                }
            });
        }
    }
    
    /**
     * Calls onWithdrawnStatement for every CatenaStatement that got killed with its TXN due to a fork.
     * 
     * @param withdrawnStack
     */
    private void queueOnWithdrawn(Stack<CatenaStatement> withdrawnStack) {
        while(!withdrawnStack.isEmpty()) {
            final CatenaStatement s = withdrawnStack.pop();
        
            for (final ListenerRegistration<CatenaStatementListener> registration : stmtListeners) {
                registration.executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        registration.listener.onStatementWithdrawn(s);
                    }
                });
            }
        }
    }
    
    private void queueOnWhistleblow(final Transaction tx, final String message) {
        for (final ListenerRegistration<CatenaWhistleblowListener> registration : whistleblowListeners) {
            registration.executor.execute(new Runnable() {
                @Override
                public void run() {
                    registration.listener.onWhistleblow(tx, message);
                }
            });
        }
    }
    
    void queueOnReorganize(final int oldNumBlocks, final int newNumBlocks, final BigInteger oldChainWork, 
            final BigInteger newChainWork) 
    {
        for (final ListenerRegistration<CatenaReorganizeListener> registration : reorgListeners) {
            registration.executor.execute(new Runnable() {
                @Override
                public void run() {
                    registration.listener.onReorganize(oldNumBlocks, newNumBlocks, oldChainWork, newChainWork);
                }
            });
        }
    }

    public void setRebootingHint(boolean isRebooting) {
        this.isRebootingHint = isRebooting;
    }
    
    public boolean isRebooting() {
        return isRebootingHint;
    }
}
