package org.catena.common;

import static com.google.common.base.Preconditions.checkState;
import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.wallet.WalletProtobufSerializer.WalletFactory;
import org.catena.client.CatenaClient;
import org.catena.common.SemaphoredWalletFactory;
import org.catena.server.CatenaServer;
import org.junit.After;
import org.junit.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Throwables;

public class ClientServerTest extends BitcoindRegtestTest {
    private static final Logger log = LoggerFactory.getLogger(ClientServerTest.class);
    
    protected Path catenaServerDataDir;	// where the Catena server stores its files
    protected CatenaServer catenaServer;
    protected boolean serverBlockingDownload = true;
    
    protected AbstractClientFactory factory;
    protected CatenaClient catenaClient;
    protected WalletFactory walletFactory = null;

    protected ECKey privKey;
    protected Address expectedChainAddr;

    protected Transaction rootOfTrustTxn;
    protected Sha256Hash txid;
    
    /**
     * Starts a bitcoinj Catena server and client.
     * Creates the root-of-trust TX and passes its ID to the client.
     *  
     * @throws IOException
     * @throws InterruptedException
     * @throws InsufficientMoneyException 
     */
    @Before
    public void setUp() throws IOException, InterruptedException, InsufficientMoneyException
    {
        // Start a Catena server (blocks to download the blockchain)
        startCatenaServer(false, generateRegtestFunds());
        
        // Create the first Catena chain TX
        txid = createRootOfTrust("testchain");
        log.debug("Created root-of-trust TXN {}", txid);
    }
    
    /**
     * Kills the bitcoinj Catena server and client.
     */
    @After
    public void tearDown()
    {
           stopCatenaClient();
           stopCatenaServer();
    }
    
    /**
     * Starts a Catena server reusing the wallet from its previous data directory.
     * 
     * @throws IOException
     * @throws InterruptedException
     */
    public void startCatenaServerReuseWallet() throws IOException, InterruptedException {
        startCatenaServer(true, null);
    }

    /**
     * Starts a Catena server, either reusing the wallet from its previous data directory (if privateKey is null) or by 
     * starting a new wallet with the specified private key.
     * 
     * @param privateKey
     * @throws IOException
     * @throws InterruptedException
     */
    public void startCatenaServer(boolean reusePrevWallet, ECKey privateKey) throws IOException, InterruptedException
    {
        checkState(catenaServer == null, "Catena server was previously started.");
        
        if(privateKey != null) {
            privKey = privateKey;
            expectedChainAddr = privateKey.toAddress(params);
        }
        
        if(!reusePrevWallet) {
            catenaServerDataDir = Files.createTempDirectory("regtest-catena-server-");
            //catenaServerDataDir.toFile().delete();
        }
        
        log.debug("Storing Catena server wallet in: " + catenaServerDataDir);
        if(!reusePrevWallet) {
            log.debug("Creating new Catena server wallet with sk={}, addr={}", privKey.getPrivateKeyAsWiF(params), 
                    expectedChainAddr);

            // We reuse the previous root-of-trust TXID (overwrite, if you want to do something different)
            catenaServer = new CatenaServer(params, catenaServerDataDir.toFile(),
                    privKey, txid);
        } else {
            log.debug("Reusing previous Catena server wallet...");
            catenaServer = new CatenaServer(params, catenaServerDataDir.toFile());
        }
        
        catenaServer.setBlockingStartup(serverBlockingDownload);
        
        if(params == RegTestParams.get()) {
            catenaServer.connectToLocalHost();
        }
        
        // Download the block chain and wait until it's done.
        catenaServer.startAsync();
        catenaServer.awaitRunning();
    }
    
    public void stopCatenaServer()
    {
        if(catenaServer != null) {
            log.debug("Shutting down Catena server...");
            if(catenaServer.isRunning()) {
                catenaServer.stopAsync();
                catenaServer.awaitTerminated();
            } else {
                log.debug("Catena server was created but is not running.");
            }
            catenaServer = null;
        } else {
            log.debug("Catena server was already shut down!");
        }
    }
    
    /**
     * Creates the first Catena TX which commits the chain's name.
     * 
     * @param chainName
     * @return
     * @throws InsufficientMoneyException
     * @throws IOException
     * @throws InterruptedException
     */
    public Sha256Hash createRootOfTrust(String chainName) throws InsufficientMoneyException, IOException, InterruptedException {
        // Create the root-of-trust TX
        rootOfTrustTxn = catenaServer.appendStatement(chainName.getBytes());
        
        // Put the statement in a block
        waitForBlock();
        
        return rootOfTrustTxn.getHash();
    }
    
    /**
     * Starts a new Catena client given a root-of-trust TXID. Client will boot-up.
     * No coins listener.
     *  
     * @param rootOfTrustTxid
     * @param start if set to true, actually starts the service
     * @throws IOException
     * @throws InterruptedException
     */
    protected void createCatenaClient(Sha256Hash rootOfTrustTxid, boolean start)
            throws IOException, InterruptedException
    {
        factory = new SimpleClientFactory(rootOfTrustTxid, expectedChainAddr, walletFactory); 
        catenaClient = factory.create(params);
        startCatenaClient(start);
    }

    protected void createSemaphoredCatenaClient(Sha256Hash rootOfTrustTxid, Semaphore semAppended, Semaphore semWithdrawn)
            throws IOException, InterruptedException {
        
        factory = new SimpleClientFactory(rootOfTrustTxid, expectedChainAddr, new SemaphoredWalletFactory(semAppended, semWithdrawn));
        catenaClient = factory.create(params);
        startCatenaClient(true);
    }
    
    protected void restartCatenaClient(boolean start) throws IOException {
        catenaClient = factory.create(params);
        
        startCatenaClient(start);
    }
    
    protected void startCatenaClient(boolean start) {
        if(params == RegTestParams.get()) {
            catenaClient.connectToLocalHost();
        }
        
        if(start) {
        	try {
	            catenaClient.startAsync();
	            catenaClient.awaitRunning();
        	} catch (Throwable e) {
        		log.debug("Could not start Catena client due to exception: " + Throwables.getStackTraceAsString(e));
        		throw e;
        	}
        }
    }
    
    public void stopCatenaClient()
    {
        //checkState(catenaClient != null, "Catena client was not even started, nothing to stop.");
    
        if(catenaClient != null) {
            log.debug("Shutting down Catena client...");
            if(catenaClient.isRunning()) {
                catenaClient.stopAsync();
                catenaClient.awaitTerminated();
            } else {
            	log.debug("Catena client was already stopped (isRunning() returned false)...");
            }
            catenaClient = null;
        } else {
            log.debug("Catena client was already shut down (catenaClient was null)!");
        }
    }
    
    protected void issueStatements(String stmts[]) throws InsufficientMoneyException, IOException, InterruptedException {
        issueStatements(stmts, 0, stmts.length);
    }
    
    protected void issueStatement(byte[] stmt) throws InsufficientMoneyException, IOException, InterruptedException {
        Transaction tx = catenaServer.appendStatement(stmt);
        log.info("Issued statement (hex): " + Utils.toHex(stmt) + " (tx " + tx.getHashAsString().substring(0, 6) + "..." + 
                ", prev " + tx.getInput(0).getConnectedTransaction().getHashAsString().substring(0, 6) + "...)");
        waitForBlock();
    }
    
    protected void issueStatements(String stmts[], int startInc, int endExcl) throws InsufficientMoneyException, IOException, InterruptedException {
        // Catena server issues some statements
        for (int i = startInc; i < endExcl; i++) {
            String s = stmts[i];
            Transaction tx = catenaServer.appendStatement(s.getBytes());
            log.info("Issued statement: " + s + " (tx " + tx.getHashAsString().substring(0, 6) + "..." + 
                    ", prev " + tx.getInput(0).getConnectedTransaction().getHashAsString().substring(0, 6) + "...)");
            waitForBlock();
        }
    }
    
    protected int waitForStatements(int num, Semaphore sem) {
        // Now we wait for client to receive them
        int acquired = 0;
        for(int i = 0; i < num; i++) {
            try {
                if(sem.tryAcquire(10, TimeUnit.SECONDS) == false) {
                    fail("Should have acquired \"sent\" semaphore by now. Acquired " + acquired + " out of a total of " + 
                            num + " sempahores");
                } else {
                    acquired++;
                }
            } catch(InterruptedException e) {
                fail("Interrupted while waiting for onCoinsSent TXs: " + Throwables.getStackTraceAsString(e));
            }
        }
        
        return acquired;
    }
    
    protected void iterativeStatementCheck(CatenaService svc, String[] stmts, boolean isFwd) {
        log.info("Iterating over Catena statements (" + (isFwd ? "forward" : "backwards") + ")...");
        Iterator<CatenaStatement> it = svc.getCatenaWallet().statementIterator(isFwd);
        
        List<String> stmtsList = Arrays.<String>asList(stmts.clone());
        if(isFwd == false)
            Collections.reverse(stmtsList);
        
        int i = 1;
        for(String stmt : stmtsList) {
            assertTrue("expected more statements in iterator", it.hasNext());
            
            CatenaStatement s = it.next();
            log.info("Statement in Catena TX #{}: {} (expecting {})", i, s.getAsString(), stmt);
            i++;
            
            try {
                assertEquals("statement in iterator did not match issued statement", stmt, s.getAsString());
            } catch(Throwable e) {
                Transaction prevTx = catenaServer.getCatenaWallet().getTransaction(s.getTxHash());
                log.error("Statement in iterator: {}, tx={}, prevTx={}", s.getAsString(), s.getTxHash(), 
                        prevTx.getInput(0).getConnectedOutput().getParentTransaction().getHash());
                throw e;
            }
        }
        
        // There should be no extra statements after
        assertFalse(it.hasNext());
    }
}
