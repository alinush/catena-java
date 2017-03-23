package org.catena.common;

import java.util.Arrays;

import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.WalletExtension;
import org.catena.server.CatenaServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.*;

/**
 * We use a wallet extension to dump the first Catena TXID to disk so that
 * both clients and the server remember the root-of-trust easily.
 * 
 * The txid is needed for easily creating iterators through the Catena chain. 
 */
public class CatenaWalletExtension implements WalletExtension {
    private static final Logger log = LoggerFactory.getLogger(CatenaWalletExtension.class);
    
    public final static String EXTENSION_ID = CatenaWalletExtension.class.getCanonicalName();
    
    /**
     * The TXID of the first Catena TX issued by server or received by client.
     */
    Sha256Hash txid = null; 
    /**
     * The name of the Catena chain or log
     */
    String name = null;

    public CatenaWalletExtension() {}
    
    /**
     * Returns true if the first TX's hash has been recorded via this extension
     * and false otherwise.
     * 
     * @return
     */
    public boolean hasRootOfTrustTxid() { return txid != null; }
    
    /**
     * The CatenaWallet class calls this method to set the root-of-trust TXID so
     * that it can be saved with the wallet. 
     * @param tx
     */
    public void setRootOfTrustTxid(Sha256Hash txid) {
        checkState(hasRootOfTrustTxid() == false, "TXID has been previosly set");
        log.trace("Set the root-of-trust TXID in the wallet extension to: " + txid);
        
        this.txid = txid;
    }
    
    public Sha256Hash getRootOfTrustTxid() {
        checkState(hasRootOfTrustTxid());
        return txid;
    }
    
    public boolean hasName() { return name != null; }
    
    public void setName(String name) {
        checkState(hasName() == false, "Name has been previosly set");
        this.name = name; 
    }
    
    public String getName() {
        checkState(hasName());
        return name;
    }

    @Override
    public String getWalletExtensionID() {
        return EXTENSION_ID;
    }

    @Override
    public boolean isWalletExtensionMandatory() {
        return true;
    }

    @Override
    public byte[] serializeWalletExtension() {
        log.trace("Writing the root-of-trust TXID to the wallet: " + (hasRootOfTrustTxid()? txid.toString() : "(no TXID yet)"));
        byte[] data = new byte[Sha256Hash.LENGTH + CatenaServer.OP_RETURN_MAX_SIZE];
        
        if(txid != null) {
            byte[] txidBytes = txid.getBytes();
            checkState(txidBytes.length == Sha256Hash.LENGTH);
            System.arraycopy(txidBytes, 0, data, 0, Sha256Hash.LENGTH);
        }
        
        if(name != null) {
            byte[] nameBytes = name.getBytes();
            checkState(nameBytes.length <= CatenaServer.OP_RETURN_MAX_SIZE);
            
            System.arraycopy(nameBytes, 0, data, Sha256Hash.LENGTH, nameBytes.length);
        }

        return data; 
    }

    @Override
    public void deserializeWalletExtension(Wallet wallet, byte[] data) {
        checkArgument(data.length == Sha256Hash.LENGTH + CatenaServer.OP_RETURN_MAX_SIZE);
        checkNotNull(wallet);
        
        byte[] txidBytes = Arrays.copyOfRange(data, 0, Sha256Hash.LENGTH);
        byte[] nameBytes = Arrays.copyOfRange(data, Sha256Hash.LENGTH, Sha256Hash.LENGTH + CatenaServer.OP_RETURN_MAX_SIZE);
        
        checkState(txidBytes.length == Sha256Hash.LENGTH);
        checkState(nameBytes.length == CatenaServer.OP_RETURN_MAX_SIZE);
        
        if(Arrays.equals(txidBytes, new byte[Sha256Hash.LENGTH]) == false)
            txid = Sha256Hash.wrap(txidBytes);
        
        if(Arrays.equals(nameBytes, new byte[CatenaServer.OP_RETURN_MAX_SIZE]) == false)
            name = new String(nameBytes);
        
        log.trace("Read back the root-of-trust TXID from wallet file: " + (txid != null ? txid.toString() : "(all zeros)"));
    }
}
