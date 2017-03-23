package org.catena.client;

//import static org.junit.Assert.*;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.Semaphore;

import org.bitcoinj.core.InsufficientMoneyException;
import org.catena.common.CatenaStatement;
import org.catena.common.CatenaUtils;
import org.catena.common.ClientServerTest;
import org.catena.common.TestUtils;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unit test for a Catena server being able to read its own chain of statements.
 * 
 * First, the test sets up a Catena chain on the blockchain.
 * Second, the test starts up a new Catena server from scratch which downloads 
 * the full blockchain and "picks up" the Catena chain that was set up earlier.
 * ("picks up" means it recognizes the transactions of that chain and keeps them in the wallet).
 * Third, the test checks that the "picked up" statement history is the same as
 * the set up statement history.
 * 
 * The test fails if:
 * - the regtest bitcoind is failed to start up
 * - there aren't enough funds to create the TXs
 */
public class ReadChainTest extends ClientServerTest
{
    private static final Logger log = LoggerFactory.getLogger(ReadChainTest.class);
    
    private static final int NUM_STATEMENTS = 5;

    private Semaphore semAppended;
    
    /**
     * Tries writing a Catena chain to the blockchain and then reads it back.
     * The client is started before the statements are issued.
     * 
     * @throws InterruptedException 
     * @throws IOException 
     */
    @Test
    public void testStartAndIssue() throws InsufficientMoneyException, IOException, InterruptedException
    {
        log.info("Test: Read statements that are issued after launching Catena client");

        // Step 1: Start a Catena client with a statement listener that 
        // releases the semaphore when it gets Catena transactions.

        semAppended = new Semaphore(0);
        createSemaphoredCatenaClient(txid, semAppended, null);
        
        String stmts[] = TestUtils.generateStatements(NUM_STATEMENTS);

        // Step 2: Issue some statements
        issueStatements(stmts);
        
        // Step 3: Wait for those statements
        waitForStatements(stmts.length, semAppended);
        
        // Step 4: Test iterator too
        iterativeStatementCheck(catenaClient, stmts, true);
        iterativeStatementCheck(catenaClient, stmts, false);
    }
    
    /**
     * The client is started AFTER the statements are issued.
     * 
     * @throws InsufficientMoneyException
     * @throws IOException
     * @throws InterruptedException
     */
    @Test
    public void testIssueAndStart() throws InsufficientMoneyException, IOException, InterruptedException
    {
        log.info("Test: Read statements that are issued before launching Catena client");
        
        String stmts[] = TestUtils.generateStatements(NUM_STATEMENTS);

        // Step 1: Issue some statements
        issueStatements(stmts);
        
        
        // NOTE: A fresh Catena wallet could download newer blocks
        // before downloading the root of trust TX's block which sets the chain
        // address and resets the Bloom filter: this means that we'll never see 
        // the chain's TXs because the newer blocks won't be redownloaded with the
        // new filter. That's why we install a CatenaStatementListener before
        // starting the client here.

        // Step 2: Launch a client with an statement listener that 
        // releases the semaphore when it gets a Catena transaction.
        
        Semaphore semAppended = new Semaphore(0);
        createSemaphoredCatenaClient(txid, semAppended, null);
        
        // Step 3: Wait for those statements
        waitForStatements(stmts.length, semAppended);
        
        // Step 4: Test iterator too
        iterativeStatementCheck(catenaClient, stmts, true);
        iterativeStatementCheck(catenaClient, stmts, false);
    }
    
    @Test
    public void testRestartReuseWallet() throws InsufficientMoneyException, IOException, InterruptedException {
        // Starts one client, shuts it down and then restarts it with the same wallet
        log.info("Test: Read statements across client restarts (reuse old wallet)");
        testRestartHelper(true);
    }
    
    @Test
    public void testRestartNewWallet() throws InsufficientMoneyException, IOException, InterruptedException {
        // Starts one client, shuts it down and then starts another client with a new wallet
        log.info("Test: Read statements across client restarts (use new wallet)");
        testRestartHelper(false);
    }
    
    public void testRestartHelper(boolean reuseWallet) throws InsufficientMoneyException, IOException, InterruptedException {
        // Issue some initial statements
        testStartAndIssue();

        // Stop the 1st client from the previous test
        log.debug("Stoping 1st Catena client...");
        log.debug("----------------------------");
        log.trace("1st client's wallet: " + CatenaUtils.summarizeWallet(catenaClient.wallet()));
        
        // Count the number of initial issued statements in the testReadBefore() call
        // (We'll need this to make sure that the restarted client still has the same
        //  number of statements)
        int numPrevStmts = catenaClient.getCatenaWallet().getNumStatements();
        
        stopCatenaClient();
        
        // Simulate a restart: start new Catena client, with the previous wallet
        // or a new wallet, as specified in 'reuseWallet'
        
        Semaphore sem;
        if(reuseWallet) {
            log.debug("Rebooting the Catena client (same wallet)...");
            
            // We're reusing the wallet here so the statement
            // listener won't be called for the initial statements, just for
            // the extra ones we issue below.
            restartCatenaClient(true);
            // Need to get the semaphore the client was created with, so we can wait for statements using it 
            sem = semAppended;
        } else {
            log.debug("Starting up a fresh 2nd Catena client (new wallet)...");            
            sem = new Semaphore(0);
            createSemaphoredCatenaClient(txid, sem, null);
            
            // We're not reusing the wallet, but creating a new one, so
            // our statement listener will be called both for the initial statements
            // we issued in testReadBefore() and the extra statements issued below.
            //
            // It looks like client might start without finishing full blockchain 
            // download so we should wait for the initial statements here.
            log.debug("Waiting for the previous {} statements", numPrevStmts);
            waitForStatements(numPrevStmts, sem);
        }
        
        // Check that the restarted wallet still has the initially issued statements
        assertEquals((reuseWallet ? "restarted" : "new") + " wallet did not have all previous statements in it",
                numPrevStmts, catenaClient.getCatenaWallet().getNumStatements());
        
        log.trace("2nd client's wallet: " + CatenaUtils.summarizeWallet(catenaClient.wallet()));
        //log.trace("2nd client's wallet full description: " + catenaClient.wallet());

        // Step 2: Issue a couple of extra statements
        String stmts[] = new String[] { "extra1", "extra2" }; 
        issueStatements(stmts);
        
        // Step 3: Wait for those extra statements and possibly the initial ones too    	
        waitForStatements(stmts.length, sem);
        
        // Step 4: Check that we received the last statements correctly
        Iterator<CatenaStatement> it = catenaClient.getCatenaWallet().statementIterator(false);
        int i = stmts.length - 1;
        assertTrue(it.hasNext());
        
        while(it.hasNext() && i >= 0) {
            String s = it.next().getAsString();
            log.debug("Extra statement in Catena TX: " + s);
            assertEquals(s, stmts[i]);
            i--;
        }
        
        assertEquals("iterator did not consume all extra statemetns", -1, i);
        
        // Consume the other statements as well
        int count = 0;
        while(it.hasNext()) {
            String s = it.next().getAsString();
            log.debug("Initial statement in Catena TX: " + s);
            count++;
        }
        assertEquals("something went wrong while iterating over initial statements after getting extra statements", 
                numPrevStmts, count);
    }
}
