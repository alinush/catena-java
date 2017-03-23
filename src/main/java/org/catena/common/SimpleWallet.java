package org.catena.common;

import static com.google.common.base.Preconditions.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.wallet.KeyChainGroup;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.WalletProtobufSerializer.WalletFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;

public class SimpleWallet extends Wallet {
    private static final Logger log = LoggerFactory.getLogger(SimpleWallet.class);
    public static final int OP_RETURN_MAX_SIZE = 80;
    
    public SimpleWallet(NetworkParameters params) {
        super(params);
        log.trace("Initializing Catena SimpleWallet (params)...");
    }
    
    public SimpleWallet(NetworkParameters params, KeyChainGroup keyChainGroup) {
        super(params, keyChainGroup);
        
        log.trace("Initializing Catena SimpleWallet (params, kcg)...");
        
        List<ECKey> keys = keyChainGroup.getImportedKeys();
        if(!keys.isEmpty()) {
            ECKey chainKey = keys.get(0);
            log.trace("Read back Catena chain's key from wallet: addr={}, sk={}", chainKey.toAddress(params),
                    chainKey.getPrivateKeyAsWiF(params));
        } else {
            log.trace("No Catena chain key to read back yet.");
        }
        
    }
    
    /**
     * Utility method for casting a Wallet object to a SimpleWallet.
     * 
     * @param w
     * @return
     */
    public static SimpleWallet castWallet(Wallet w) {
        checkState(w instanceof SimpleWallet);
        
        return (SimpleWallet)w;
    }
    
    public static class Factory implements WalletFactory {
    
        @Override
        public SimpleWallet create(NetworkParameters params, KeyChainGroup keyChainGroup) {
            // Need our own custom Wallet subclass in CatenaService
            return new SimpleWallet(params, keyChainGroup);
        }
    }

    /**
     * Returns the chain's key.
     * @return
     */
    public ECKey getChainKey() {
        List<ECKey> keys = getImportedKeys();
        checkState(!keys.isEmpty(), "chain key has not been set in wallet yet");
        return keys.get(0);
    }
    
    /**
     * Returns the chain's address.
     * @return
     */
    public Address getChainAddress() {
        return getChainKey().toAddress(getParams());
    }
    
    /**
     * Returns the Catena wallet extension that saves the first Catena TX ID to the wallet file.
     * 
     * @return
     */
    public CatenaWalletExtension getCatenaExtension() {
        return (CatenaWalletExtension)getExtensions().get(CatenaWalletExtension.EXTENSION_ID);
    }
    
    /**
     * Appends a statement to the Catena chain. This function first commits
     * the statement on disk, then schedules it for publication on the 
     * blockchain.
     * 
     * @param statement
     * @throws InsufficientMoneyException
     * 
     * @return
     */
    public Transaction appendStatement(byte[] statement) throws InsufficientMoneyException {
        return appendStatement(statement, true);
    }
    
    public Transaction appendStatement(byte[] data, boolean commit) throws InsufficientMoneyException {
        if(data.length > OP_RETURN_MAX_SIZE) {
            throw new RuntimeException("OP_RETURN data cannot exceed 80 bytes");
        }
        
        CatenaWalletExtension ext = getCatenaExtension();
        checkNotNull(ext);
        Transaction tx = new Transaction(params);
        TransactionOutput prevLink;
        boolean isRootOfTrustTx = ext.hasRootOfTrustTxid() == false;
        
        //log.trace("UTXO for Catena '" + statement + "': " + prevLink);
        
        String summary;
        if(isRootOfTrustTx) {
            int numUnspents = this.getUnspents().size();
            checkState(numUnspents == 1, "Expected only one funding UTXO, got " + numUnspents);
            
            summary = "Funding TX for Catena chain starting with";
            prevLink = this.getUnspents().get(0);
        } else {
            summary = "Previous Catena TX for";
            prevLink = this.getLastUtxo();
        }
        log.trace(summary + " '" + Utils.toHex(data) + "': " + prevLink.getParentTransaction());
        
        // WARNING: This code might break in future versions of bitcoinj, depending on
        // how the coin selection and the fee calculation code in bitcoinj changes in
        // the Wallet class. In v0.14.3, we create a Catena tx with one input and 
        // two outputs, fully spending the input. Then, we assume bitcoinj subtracts
        // the tx fee from the value of the 1st output, creating no extra outputs.
        // This assumption might break in future versions though.
        
        tx.addInput(prevLink);

        Coin feeAmt = Transaction.REFERENCE_DEFAULT_MIN_TX_FEE;
        Coin opRetAmt = Transaction.MIN_NONDUST_OUTPUT;
        //Coin changeAmt = prevLink.getValue().minus(opRetAmt);
        Coin changeAmt = prevLink.getValue().minus(opRetAmt).minus(feeAmt);
        
        // 1st output is the chain's next link
        tx.addOutput(changeAmt, getChainAddress());
        // 2nd output is the chain's data
        tx.addOutput(opRetAmt, ScriptBuilder.createOpReturnScript(data));
        
        SendRequest req = SendRequest.forTx(tx);
        // Catena TXs need inputs and outputs to be ordered correctly
        req.shuffleOutputs = false;
        req.ensureMinRequiredFee = true;
        // TODO: Set req.feePerKB just to be sure!

        //log.trace("Catena server SendRequest for '" + statement + "' before completeTx: " + req);
        this.sendCatenaTxOffline(req, isRootOfTrustTx, commit);
        
        // If this was the first issued statement, then remember this 
        // txid in the wallet extension so we can survive reboots.
        if(isRootOfTrustTx) {
            log.info("Created root-of-trust TX: " + tx.getHashAsString());
        
            ext.setRootOfTrustTxid(tx.getHash());
            ext.setName(new String(data));
            saveNow();
        }

        // NOTE: At this point, the TX is saved in the wallet! No need to worry
        // about accidental double spending or forgetting statements! 
        // 
        // See https://bitcoinj.github.io/working-with-the-wallet#learning-about-changes
        //  "By default the Wallet is just an in-memory object, it won’t save by itself. 
        //   You can use saveToFile() or saveToOutputStream() when you want to persist 
        //   the wallet. It’s best to use saveToFile() if possible because this will 
        //   write to a temporary file and then atomically rename it, giving you
        //   assurance that the wallet won’t be half-written or corrupted if something 
        //   goes wrong half way through the saving process.")
        
        return tx;
    }

    /**
     * A modified adjustOutputDownwardsForFee that doesn't consider any extra inputs
     * because Catena TXs only have a single input.
     * 
     * Reduce the value of the first output of a transaction to pay the given feePerKb as appropriate for its size. 
     */
    private boolean payFee(Transaction tx, Coin feePerKb, boolean ensureMinRequiredFee) {
        final int size = tx.unsafeBitcoinSerialize().length;
        Coin fee = feePerKb.multiply(size).divide(1000);
        
        if (ensureMinRequiredFee && fee.compareTo(Transaction.REFERENCE_DEFAULT_MIN_TX_FEE) < 0)
            fee = Transaction.REFERENCE_DEFAULT_MIN_TX_FEE;
        
        TransactionOutput output = tx.getOutput(0);
        output.setValue(output.getValue().subtract(fee));
        
        return !output.isDust();
    }
    
    /**
     * A modified completeTx that gets rid of all the extra inputs/outputs added
     * to the TX, which we don't want since Catena TXs need to be one-input 
     * two-output TXs.
     * 
     * @param req
     * @throws InsufficientMoneyException
     */
    public void completeCatenaTx(SendRequest req) throws InsufficientMoneyException {
        lock.lock();
        try {
            // Print the output value
            Coin value = Coin.ZERO;
            for (TransactionOutput output : req.tx.getOutputs()) {
                value = value.add(output.getValue());
            }

            log.trace("Completing send tx with {} outputs totalling {} (not including fees)",
                    req.tx.getOutputs().size(), value.toFriendlyString());

            // Check for dusty sends and the OP_RETURN limit.
            if (req.ensureMinRequiredFee && !req.emptyWallet) { // Min fee checking is handled later for emptyWallet.
                int opReturnCount = 0;
                for (TransactionOutput output : req.tx.getOutputs()) {
                    if (output.isDust())
                        throw new DustySendRequested();
                    if (output.getScriptPubKey().isOpReturn())
                        ++opReturnCount;
                }
                if (opReturnCount > 1) // Only 1 OP_RETURN per transaction allowed.
                    throw new MultipleOpReturnRequested();
            }

            // Pay for the TX fee, depending on the TX size.
            Coin feePerKb = req.feePerKb == null ? Coin.ZERO : req.feePerKb;
            if (!payFee(req.tx, feePerKb, req.ensureMinRequiredFee))
                throw new CouldNotAdjustDownwards();

            // Now sign the inputs, thus proving that we are entitled to redeem the connected outputs.
            if (req.signInputs)
                signTransaction(req);

            // Check size.
            final int size = req.tx.unsafeBitcoinSerialize().length;
            if (size > Transaction.MAX_STANDARD_TX_SIZE)
                throw new ExceededMaxTransactionSize();

            final Coin calculatedFee = req.tx.getFee();
            if (calculatedFee != null)
                log.trace("  with a fee of {}/kB, {} for {} bytes",
                        calculatedFee.multiply(1000).divide(size).toFriendlyString(), calculatedFee.toFriendlyString(),
                        size);

            // Label the transaction as being self created. We can use this later to spend its change output even before
            // the transaction is confirmed. We deliberately won't bother notifying listeners here as there's not much
            // point - the user isn't interested in a confidence transition they made themselves.
            req.tx.getConfidence().setSource(TransactionConfidence.Source.SELF);
            // Label the transaction as being a user requested payment. This can be used to render GUI wallet
            // transaction lists more appropriately, especially when the wallet starts to generate transactions itself
            // for internal purposes.
            req.tx.setPurpose(Transaction.Purpose.USER_PAYMENT);
            // Record the exchange rate that was valid when the transaction was completed.
            req.tx.setExchangeRate(req.exchangeRate);
            req.tx.setMemo(req.memo);
            //req.completed = true;	// FIXME: This field is private, can't set it to true, but thankfully this is just for debugging.
            log.trace("  completed: {}", req.tx);
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * A modified sendCoinsOffline, for Catena TXs purposes.
     * 
     * @param request
     * @param commit 
     * @return
     * @throws InsufficientMoneyException
     */
    public Transaction sendCatenaTxOffline(SendRequest request, boolean isRootOfTrustTxn, boolean commit) throws InsufficientMoneyException {
        // First we check a properly-structured Catena TX was given to us
        Address addr = request.tx.getOutput(0).getAddressFromP2PKHScript(request.tx.getParams());
        TransactionOutput utxo = request.tx.getInput(0).getConnectedOutput();
        
        // We don't have a signature yet (completeCatenaTx() call below will sign)
        if(!CatenaUtils.isCatenaTxNoSig(request.tx, addr, utxo, isRootOfTrustTxn == false)) {
            throw new RuntimeException("You must provide a Catena TX as input to this method, not: " + request.tx);
        }
        
        lock.lock();
        try {
            // Modified Wallet::completeTx and got rid of unnecessary complexities
            completeCatenaTx(request);
            
            // Make sure that bitcoinj didn't mess with our TX inputs/outputs too 
            // much when funding it and computing its tx fee.            
            if(!CatenaUtils.isSignedCatenaTx(request.tx, addr, utxo, isRootOfTrustTxn == false)) {
                throw new RuntimeException("bitcoinj meddled with the Catena tx too much: " + request.tx);
            }
            
            // When generating lies, for testing code, we don't commit the lies because otherwise we can't double-spend.
            if(commit)
                commitTx(request.tx);
            return request.tx;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Returns the last UTXO in the Catena chain used to fund and link the next TX
     * that commits the next statement.
     * If there is more than one UTXO, a warning is issued, since that case should never arise.
     */
    public TransactionOutput getLastUtxo() {
        List<TransactionOutput> utxos = getUnspents();
        ArrayList<TransactionOutput> chainKeyUtxos = new ArrayList<TransactionOutput>();
        Address addr = getChainAddress();
        
        for(TransactionOutput o : utxos) {
            if(o.isMineOrWatched(this) && o.getAddressFromP2PKHScript(params).equals(addr)) {
                chainKeyUtxos.add(o);
            } else {
                log.trace("Ineligible UTXO (mine=" + o.isMineOrWatched(this) + 
                        ", to=" + o.getAddressFromP2PKHScript(params) +
                        ", chainAddr=" + addr +"): " + o);
            }
        }
        
        // There should be exactly one Catena UTXO
        if(chainKeyUtxos.size() > 1) {
            log.warn("More than one UTXO spendable by '" + addr + "': " + chainKeyUtxos.size());
        }
        
        if(chainKeyUtxos.isEmpty()) {
            throw new RuntimeException("No UTXOs spendable by '" + addr + "'");
        }
        
        // Make sure the UTXO we found is from a Catena TX: does not check sigs,
        // checks the address in the TX's output is the right one, does not check the previous link
        TransactionOutput lastCatenaUtxo = chainKeyUtxos.get(0);
        if(CatenaUtils.maybeCatenaTx(lastCatenaUtxo.getParentTransaction(), addr) == false) {
            throw new RuntimeException("Expected to get an UTXO of a Catena TX.");
        }
        
        return lastCatenaUtxo;
    }
    
    @VisibleForTesting
    public int getNumStatements() {
        lock.lock();
        try {
            return txnsToList(true).size();
        } finally {
            lock.unlock();
        }
    }
    
    @VisibleForTesting
    public Iterator<CatenaStatement> statementIterator(boolean isFwd) {
        lock.lock();
        try {

            List<CatenaStatement> stmts = txnsToList(isFwd);
            // NOTE: the root-of-trust TXN is not part of the list
            
            return Iterators.unmodifiableIterator(stmts.iterator());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a list of the Catena transactions in this wallet.
     * Used for testing and in the Catena command line app.
     * 
     * @param isFwd
     * @return
     */
    private List<CatenaStatement> txnsToList(boolean isFwd) {
        CatenaWalletExtension ext = getCatenaExtension();
        
        if(!ext.hasRootOfTrustTxid()) {
            return ImmutableList.<CatenaStatement>of();
        }
        
        Transaction tip;
        // We skip the root-of-trust TXN
        Transaction rootTxn = getTransaction(ext.getRootOfTrustTxid());
        tip = CatenaUtils.getNextCatenaTx(this, rootTxn);
        
        if(tip == null) {
            return ImmutableList.<CatenaStatement>of();
        }
         
        checkState(tip.getInput(0).getOutpoint().equals(rootTxn.getOutput(0).getOutPointFor()));
        
        // Copy the transactions from the wallet as they could get modified by reorganizations, etc.
        LinkedList<CatenaStatement> stmts = new LinkedList<CatenaStatement>();
        
        // Create and copy the statements in the array
        while(tip != null) {
            CatenaStatement s = CatenaStatement.fromTxn(tip);
            
            if(isFwd) {
                stmts.addLast(s);
            } else {
                stmts.addFirst(s);
            }
            
            tip = CatenaUtils.getNextCatenaTx(this, tip);
        }
        return stmts;
    }

}
