package org.catena.client;

//import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.*;
import static org.junit.Assert.assertTrue;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.ProtocolException;
import org.bitcoinj.core.PrunedException;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.MemoryBlockStore;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.listeners.WalletCoinsReceivedEventListener;
import org.bitcoinj.wallet.listeners.WalletReorganizeEventListener;
import org.catena.client.ClientWallet;
import org.catena.common.CatenaWalletExtension;
import org.catena.common.SemaphoredStatementListener;
import org.catena.common.SummarizedTest;
import org.catena.common.TestUtils;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

/**
 * Testing that Catena clients can handle accidental and malicious blockchain forks.
 */
public class ReorganizeChainTest extends SummarizedTest {
    private static final Logger log = LoggerFactory.getLogger(ReorganizeChainTest.class);
    
    private Semaphore semAppended;
    private Semaphore semWithdrawn;
    
    private static final NetworkParameters params = RegTestParams.get();
    private ClientWallet wallet;
    private BlockChain chain;
    
    //private Sha256Hash rootOfTrustTxid;
    //private Transaction rootOfTrustTxn;
    private List<Transaction> txs = new ArrayList<Transaction>();
    private List<Transaction> lies = new ArrayList<Transaction>();
    
    private Address genCoinsAddr;   // coinbase rewards go here
    private ECKey genCoinsKey;      // coinbase rewards spendable by this
    private Address blackholeAddr;  // funds send here are lost forever
    
    private Block lastBlock;
    
    @Before
    public void setUp() throws Exception {
        Context.propagate(new Context(params, 10000, Transaction.DEFAULT_TX_FEE, true));

        semAppended = new Semaphore(0);
        semWithdrawn = new Semaphore(0);
        
        // Step 0: Catena wallet already started with a listener that signals when statements are appended and withdrawn
        wallet = new ClientWallet(params);
        wallet.addExtension(new CatenaWalletExtension());
        
        // Generate a key to send mining rewards to 
        genCoinsKey = new ECKey();
        wallet.importKey(genCoinsKey);
        genCoinsAddr = genCoinsKey.toAddress(params);
        wallet.addWatchedAddress(genCoinsAddr); // need to tell the wallet about the chain address by calling this

        // Generate a black hole key, where funds are send to be lost
        blackholeAddr = new ECKey().toAddress(params);
        
        // Add semaphores to wallet
        wallet.addStatementListener(new SemaphoredStatementListener(wallet, semAppended, semWithdrawn));
        wallet.addReorganizeEventListener(new WalletReorganizeEventListener() {
            @Override
            public void onReorganize(Wallet wallet) {
                log.debug("Fork detected!");
            }
        });
        
        // TODO: can set an onReorganize listener here
        chain = new BlockChain(params, wallet, new MemoryBlockStore(params));

        // Set a listener to wait for those coins
        final Semaphore sem = new Semaphore(0);
        wallet.addCoinsReceivedEventListener(new WalletCoinsReceivedEventListener() {

            @Override
            public void onCoinsReceived(Wallet wallet, Transaction tx,
                    Coin prevBalance, Coin newBalance) {
                //log.debug("Got initial coins from mining reward: {}", newBalance);
                sem.release();
            }
        });

        // Create the first block, so we can get some coins.
        lastBlock = params.getGenesisBlock().createNextBlock(genCoinsAddr);
        chain.add(lastBlock);
        // Bury the first block with enough blocks, so we can spend those coins
        for(int i = 0; i < params.getSpendableCoinbaseDepth(); i++) {
            lastBlock = lastBlock.createNextBlock(blackholeAddr);
            chain.add(lastBlock);
        }
        
        log.debug("Created {} blocks.", chain.getBestChainHeight());
        
        assertTrue("timed out waiting to receive coins from block reward", sem.tryAcquire(10, TimeUnit.SECONDS));
        
        // Create the root-of-trust TXN
        Transaction tx = issueStatement("testchain", false);
        log.debug("Created root-of-trust TXN {}", tx.getHash());
        //wallet.getCatenaExtension().setRootOfTrustTxid(rootOfTrustTxid); // already set by issueStatement
        wallet.addChangeEventListener(Threading.SAME_THREAD, new CatenaWalletListener(wallet));
    }

    private Block genNewBlock(Block prevBlock) throws PrunedException {
        return genNewBlock(prevBlock, null);
    }
    
    private Block genNewBlock(Block prevBlock, Transaction tx) throws PrunedException {
        Block newBlock = prevBlock.createNextBlock(blackholeAddr);
        if(tx != null) {
            newBlock.addTransaction(tx);
            newBlock.solve();
        }
        
        chain.add(roundtrip(newBlock));
        
        log.debug("Generated block: prev {}..., new {}...", prevBlock.getHashAsString().substring(0, 7), 
                newBlock.getHashAsString().substring(0, 7));
        
        return newBlock;
    }
    
    private Transaction issueStatement(String stmt) throws InsufficientMoneyException, PrunedException {
        return issueStatement(stmt, true);
    }
    
    /**
     * This function issues a statement and generates a block with the corresponding TXN. At the same time, it also
     * creates a lying TXN that double spends the previous TXN. We'll need the lying TXN when we test lie detection.
     * 
     * @param stmt
     * @param isRootOfTrust
     * @return the actual non-lying TXN
     * 
     * @throws InsufficientMoneyException
     * @throws PrunedException
     */
    private Transaction issueStatement(String stmt, boolean isRootOfTrust) throws InsufficientMoneyException, PrunedException {
        // WARNING: This lying call must happen before the non-lie call. For every issued statement, we also prepare a lie.
        Transaction lie = isRootOfTrust ? wallet.appendStatement((stmt + "-lie").getBytes(), false) : null;
        Transaction txn = wallet.appendStatement(stmt.getBytes());
        
        // Make sure we lied correctly, lol.
        lastBlock = genNewBlock(lastBlock, txn);
        
        // We don't record the root-of-trust txn
        if(isRootOfTrust) {
            checkState(lie.getInput(0).getOutpoint().equals(txn.getInput(0).getOutpoint()));
            txs.add(txn);
            lies.add(lie);
        }
        
        return txn;
    }
        
    private void reissueStatement(int i) throws PrunedException {
        Transaction tx = txs.get(i);
        lastBlock = genNewBlock(lastBlock, tx);
    }
    
    private void reissueLie(int i, boolean generateBlock) throws PrunedException {
        Transaction tx = lies.get(i);
        if(generateBlock)
            lastBlock = genNewBlock(lastBlock, tx);
        else
            wallet.receivePending(tx, ImmutableList.<Transaction>of());
    }

    
    private void fork(int numBlocksDropped, int numBlocksCreated) throws BlockStoreException, PrunedException {
        Block last = lastBlock;
        
        //log.debug("Backtracing through the last {} block(s)", numBlocksDropped);
        // "Drop" blocks
        for(int i = 0; i < numBlocksDropped; i++) {
            // Get the previous block
            last = chain.getBlockStore().get(last.getPrevBlockHash()).getHeader();
            //log.debug("blockhash {}", last.getHash());
        }
        
        log.debug("Forking at block {}...", last.getHashAsString().substring(0, 7));
        
        // Create new blocks after the dropped blocks
        for(int i = 0; i < numBlocksCreated; i++) {
            last = genNewBlock(last);
        }
        
        lastBlock = last;
    }
    
    private Block roundtrip(Block b) throws ProtocolException {
        return params.getDefaultSerializer().makeBlock(b.bitcoinSerialize());
    }
    
    /**
     * Fork the blockchain, withdraw statements, reissue them, issue new ones. Repeat a couple of times. Check that at 
     * the end all Catena TXs are still in the chain. The goal is to test our fork handling code.
     * 
     * @throws InsufficientMoneyException
     * @throws IOException
     * @throws InterruptedException
     * @throws PrunedException 
     * @throws BlockStoreException 
     */
    @Test
    public void testFork1() throws InsufficientMoneyException, IOException, InterruptedException, BlockStoreException, PrunedException {

        int numForks = 5;    // # of times we'll cause a fork switch
        int stmtsPerFork = 3;   // # of statements we'll issue before each fork switch
        int stmtsWithdrawn = 2; // # of statements we'll withdraw with each fork switch
        int numStmts = (numForks + 1) * stmtsPerFork;
        
        // Generate statements, needed in the test case functions
        String[] stmts = TestUtils.generateStatements(numStmts);
        
        log.info("Test: Issue, withdraw, reissue, and issue some more");

        // Step 1: Issue initial statements, before the first fork
        int c = 0;
        log.debug("Issuing initial statements...");
        for(int i = 0; i < stmtsPerFork; i++) {
            issueStatement(stmts[c]);
            c++;
        }
        
        // Step 2: Fork, withdrawing some statements, reissuing them and issuing some new ones
        log.debug("Creating forks...");
        for(int i = 0; i < numForks; i++) {
            log.debug("Fork #{}...", i+1);
            fork(stmtsWithdrawn, stmtsWithdrawn + 1);
            
            // Wait for the withdrawn statements to be handled
            assertTrue("did not get notified about withdrawn statements",
                    semWithdrawn.tryAcquire(stmtsWithdrawn, 5, TimeUnit.SECONDS));
            
            // Reissue withdrawn statements (careful about order, must satisfy TX dependecies)
            for(int j = 0; j < stmtsWithdrawn; j++) {
                reissueStatement(c - stmtsWithdrawn + j);
            }
            
            // Wait for reissued statements
            assertTrue("did not get notified about reissued statements", 
                    semAppended.tryAcquire(stmtsWithdrawn, 5, TimeUnit.SECONDS));
            
            // Issue new statements
            for(int j = 0; j < stmtsPerFork; j++) {
                issueStatement(stmts[c]);
                c++;
            }
            
            // Wait for newly issued statements
            assertTrue("did not get notified about newly issued statements", 
                    semAppended.tryAcquire(stmtsPerFork, 5, TimeUnit.SECONDS));
        }

        
    }
    
    @Test
    public void testFork2() throws InsufficientMoneyException, PrunedException, BlockStoreException, InterruptedException {
        Semaphore sem = testFork2or3(true);
        
        // Step 4: Lie about the 2nd TX
        reissueLie(1, true);
        
        assertTrue("did not catch Catena server lying", sem.tryAcquire(5, TimeUnit.SECONDS));
    }
    
    @Test
    public void testFork3() throws InsufficientMoneyException, PrunedException, BlockStoreException, InterruptedException {
        // The reissued lie is not in a block. It's given directly to the Wallet with wallet.receivePending which should 
        // kill the TXN and trigger our updateCatenaLog code, which should catch the lie.
        Semaphore sem = testFork2or3(false);
        
        // Step 4: Lie about the 2nd TX
        reissueStatement(1);
        reissueLie(1, false);
        
        assertTrue("did not catch Catena server lying", sem.tryAcquire(5, TimeUnit.SECONDS));
    }
    
    public Semaphore testFork2or3(boolean generateBlock) throws InsufficientMoneyException, PrunedException, 
        BlockStoreException, InterruptedException {
        log.info("Test: Issue, withdraw, lie ({})", generateBlock ? "with confirmed TX" : "with pending TX");
        
        // Generate statements, needed in the test case functions
        int numStmts = 3;
        String[] stmts = TestUtils.generateStatements(numStmts);
        
        log.info("Test: Issue, withdraw, reissue, and issue some more");

        // Step 1: Issue initial statements, before the first fork
        log.debug("Issuing initial statements...");
        for(int i = 0; i < numStmts; i++) {
            issueStatement(stmts[i]);
        }
        
        // Step 2: Drop the last numStmts - 1 blocks, create another fork longer by 1 than the dropped blocks
        int stmtsWithdrawn = numStmts - 1;
        fork(stmtsWithdrawn, stmtsWithdrawn + 1);
        

        // Wait for the withdrawn statements to be handled
        assertTrue("did not get notified about withdrawn statements",
                semWithdrawn.tryAcquire(stmtsWithdrawn, 5, TimeUnit.SECONDS));
        
        // Step 3: Install a "lie detector"
        final Semaphore sem = new Semaphore(0);
        wallet.addWhistleblowListener(new CatenaWhistleblowListener() {
            @Override
            public void onWhistleblow(Transaction tx, String message) {
                Context.propagate(wallet.getContext());
                
                log.debug("Whistleblowing for TX {}: {}", tx.getHash(), message);
                sem.release();
            }
        });
        
        return sem;
    }
}
