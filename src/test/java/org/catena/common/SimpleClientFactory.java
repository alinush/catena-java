package org.catena.common;

//import static com.google.common.base.Preconditions.checkState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.wallet.WalletProtobufSerializer.WalletFactory;
import org.catena.client.CatenaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimpleClientFactory implements AbstractClientFactory {
    private static final Logger log = LoggerFactory.getLogger(SimpleClientFactory.class);
    
    private static final int NUM_CONFIRMATIONS = 1;
    
    private Sha256Hash txid;
    private Path dataDir;
    private Address expectedChainAddr;
    private WalletFactory walletFactory;
    
    protected SimpleClientFactory(Sha256Hash txid, Address expectedChainAddr, Path dataDir, WalletFactory walletFactory) throws IOException {
        this.txid = txid;
        this.expectedChainAddr = expectedChainAddr;
        this.dataDir = dataDir;
        this.walletFactory = walletFactory;
    }
    
    public SimpleClientFactory(Sha256Hash txid, Address expectedChainAddr, WalletFactory walletFactory) throws IOException {
        this(txid, expectedChainAddr, Files.createTempDirectory("regtest-catena-client-"), walletFactory);
    }

    public CatenaClient create(NetworkParameters params) throws IOException {
        log.debug("Starting Catena client (txid=" + txid + 
                ", reuseDir=" + (txid == null) + 
                ", wallet=" + dataDir + ")...");
        
        CatenaClient client;
        
        
        client = new CatenaClient(params, dataDir.toFile(), txid, expectedChainAddr, walletFactory);
        client.setNumConfirmationsRequired(NUM_CONFIRMATIONS);
        
        return client;
    }
    
}
