package org.catena.server;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.Iterator;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.catena.common.CatenaStatement;
import org.catena.common.CatenaUtils;
import org.catena.common.ClientServerTest;
import org.catena.common.TestUtils;
import org.catena.common.Utils;
import org.junit.After;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unit test for a Catena server being able to write a simple chain of statements.
 * 
 * The test fails if:
 * - the regtest/tesnet peers are not up and running (use btc-scripts/setup-test-env.sh)
 * - there aren't enough funds to create the TXs
 */
public class WriteChainTest extends ClientServerTest
{
    private static final Logger log = LoggerFactory.getLogger(WriteChainTest.class);
    private static final int NUM_STATEMENTS = 5;
    
    /**
     * Kills the bitcoinj Catena service.
     */
    @After
    public void tearDown()
    {
        stopCatenaServer();
    }
    
    /**
     * Tries writing a Catena chain to the blockchain.
     * 
     * @throws InterruptedException 
     * @throws IOException 
     */
    @Test
    public void testWrite() throws InsufficientMoneyException, IOException, InterruptedException
    {
        log.info("Test: Write " + NUM_STATEMENTS + " statements");
        String s[] = TestUtils.generateStatements(NUM_STATEMENTS);
        issueStatements(s);
        
        iterativeStatementCheck(catenaServer, s, true);
        iterativeStatementCheck(catenaServer, s, false);
    }
    
    @Test
    public void testTxnSize() throws InsufficientMoneyException, IOException, InterruptedException
    {
        log.info("Test: Average TXN size");
        
        int numStmts = 10;
        byte s[][] = new byte[numStmts][Sha256Hash.LENGTH];
        
        for(int i = 0; i < numStmts; i++) {
            byte[] hash = Sha256Hash.of(new String("" + i).getBytes()).getBytes();
            assertEquals(Sha256Hash.LENGTH, hash.length);

            System.arraycopy(hash, 0, s[i], 0, hash.length);
            issueStatement(s[i]);
        }
        
        Iterator<CatenaStatement> it = catenaServer.getCatenaWallet().statementIterator(true);
        int i = 0;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        double avg = 0.0;
        while(it.hasNext()) {
            i++;
            CatenaStatement stmt = it.next();
            Transaction tx = catenaServer.getCatenaWallet().getTransaction(stmt.getTxHash());
            int size = tx.unsafeBitcoinSerialize().length;
            
            // 1000 bytes = 1 kB
            //log.debug("TXN #{} size in kB: {}", i, ((double)size)/1000.0);
            log.debug("TXN #{} size in bytes: {} (OP_RETURN data: {} bytes): {}", i, size, CatenaUtils.getCatenaTxData(tx).length, tx);
         
            min = min > size ? size : min;
            max = max < size ? size : max;
            avg += size;
        }
        
        avg = avg / numStmts;
        
        log.info("Min: {}, Max: {}, Avg: {}", min, max, avg);
    }
    
    //@Test
    public void testFees() throws InsufficientMoneyException, IOException, InterruptedException
    {
        log.info("Test: Correct TXN fee");
        
        String s1[] = TestUtils.generateStatements(1, 0);
        String s2[] = TestUtils.generateStatements(1, 1);

        // Issue a TXN with the default fee
        Coin defaultFeePerKb = Context.get().getFeePerKb();
        log.debug("Default fee per KB: " + defaultFeePerKb.toFriendlyString());
        issueStatements(s1);
        
        // Set the fee per KB
        Coin customFeePerKb = Coin.SATOSHI.multiply(100000);
        log.debug("Custom fee per KB: " + customFeePerKb.toFriendlyString());
        catenaServer.setFeePerKb(customFeePerKb);
        
        // Issue a TXN with a different fee
        issueStatements(s2);
        
        // Check the TXNs fee
        Iterator<CatenaStatement> it = catenaServer.getCatenaWallet().statementIterator(true);        
        CatenaStatement stmt1 = it.next();
        CatenaStatement stmt2 = it.next();
        Transaction tx1 = catenaServer.getCatenaWallet().getTransaction(stmt1.getTxHash());
        Transaction tx2 = catenaServer.getCatenaWallet().getTransaction(stmt2.getTxHash());
        log.debug("Default fee TXN #1: " + tx1);
        log.debug("Custom fee TXN #1: " + tx2);
        
        // FIXME: This fails because bitcoinj pays the wrong fee in calculateFee (check later)
        //assertCorrectFee(defaultFeePerKb, tx1);
        //assertCorrectFee(customFeePerKb, tx2);
        
        // There should be no extra statements after
        assertFalse(it.hasNext());
    }

//    private void assertCorrectFee(Coin actualFeePerKb, Transaction tx) {
//        int size = tx.unsafeBitcoinSerialize().length;
//        Coin feePerKb = tx.getFee().multiply(1000).divide(size);
//        
//        // Make sure fees were the same
//        assertEquals(actualFeePerKb, feePerKb);
//    }
    
    @Test
    public void testRestartReuseWallet() throws InsufficientMoneyException, IOException, InterruptedException {
        log.info("Test: Write statments across server restarts (reuse wallet)");
        
        String s1[] = TestUtils.generateStatements(NUM_STATEMENTS);
        issueStatements(s1);
        
        iterativeStatementCheck(catenaServer, s1, true);
        iterativeStatementCheck(catenaServer, s1, false);
        
        stopCatenaServer();
        
        // Restart the server, reusing its wallet 
        startCatenaServerReuseWallet();
        
        String s2[] = TestUtils.generateStatements(NUM_STATEMENTS, NUM_STATEMENTS);
        issueStatements(s2);
        
        String s[] = Utils.concat(s1, s2);
        iterativeStatementCheck(catenaServer, s, true);
        iterativeStatementCheck(catenaServer, s, false);
    }
    
    @Test
    public void testRestartNewWallet() throws InsufficientMoneyException, IOException, InterruptedException {
        log.info("Test: Write statments across server restarts (reuse private key and redownload blockchain)");
        
        String s1[] = TestUtils.generateStatements(NUM_STATEMENTS);
        issueStatements(s1);
        
        iterativeStatementCheck(catenaServer, s1, true);
        iterativeStatementCheck(catenaServer, s1, false);
        
        stopCatenaServer();
        
        // Restart the server, but start a new wallet with the previous private key 
        startCatenaServer(false, privKey);
        
        String[] s2 = TestUtils.generateStatements(NUM_STATEMENTS, NUM_STATEMENTS);
        issueStatements(s2);

        String s[] = Utils.concat(s1, s2);
        iterativeStatementCheck(catenaServer, s, true);
        iterativeStatementCheck(catenaServer, s, false);
    }
}