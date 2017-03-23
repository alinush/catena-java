package org.catena.client;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.wallet.KeyChainGroup;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.WalletProtobufSerializer.WalletFactory;
import org.catena.common.ClientServerTest;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.Service.State;

public class WrongPkTest extends ClientServerTest {

    private static final Logger log = LoggerFactory.getLogger(WrongPkTest.class);
    
    /**
     * Couldn't make this test work initially, not easily at least. Because the root-of-trust TXN is not saved in the wallet
     * when the wallet is initialized with the wrong PK, since the TXN doesn't transfer any funds and seems irrelevant
     * to the wallet. As a result, processRootOfTrustTxn fails because there's no TXN in the wallet. So we had to call
     * processRootOfTrustTxn manually here.
     * 
     * @throws InsufficientMoneyException
     * @throws IOException
     * @throws InterruptedException
     * @throws TimeoutException
     */
    @Test
    public void testWrongPk() throws InsufficientMoneyException, IOException, InterruptedException, TimeoutException
    {
        log.info("Test: Fail if the chain PK in the root-of-trust TX differs from the expected one");
        
        // Replace the expected chain PK with a random one.
        ECKey key = new ECKey();
        super.expectedChainAddr = key.toAddress(params);
        
        // The Catena client should stop when it encounters a different PK in the root-of-trust TX
        final Semaphore sem = new Semaphore(0);
        walletFactory = new WalletFactory() {
            @Override
            public Wallet create(NetworkParameters params, KeyChainGroup keyChainGroup) {
                log.info("Creating custom wallet that whistleblows on a wrong chain address...");
                ClientWallet wallet = new ClientWallet.Factory().create(params, keyChainGroup);
                
                wallet.addWhistleblowListener(new CatenaWhistleblowListener() {

                    @Override
                    public void onWhistleblow(Transaction tx, String message) {
                        log.info("Whistleblowing for tx {}: {}", tx.getHash(), message);
                        sem.release();
                        catenaClient.stopAsync();
                    }
                    
                });
                
                return wallet;
            }   
        };
        
        createCatenaClient(txid, false);
        catenaClient.startAsync();
        catenaClient.awaitRunning();
        
        // We are cheating here: We're feeding the Catena server-created root-of-trust TXN to the Catena client manually 
        catenaClient.getCatenaWallet().processRootOfTrustTxn(rootOfTrustTxn);
        Assert.assertTrue("Catena client should have whistleblown due to wrong PK", sem.tryAcquire(5, TimeUnit.SECONDS));
        
        catenaClient.stopAsync();
        catenaClient.awaitTerminated(3, TimeUnit.SECONDS);
        Assert.assertTrue(catenaClient.state() == State.TERMINATED);
    }
    
}
