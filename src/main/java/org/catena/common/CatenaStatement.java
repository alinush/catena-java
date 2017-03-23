package org.catena.common;

import static com.google.common.base.Preconditions.*;

import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.spongycastle.util.Arrays;

public class CatenaStatement {

    private byte[] data;
    private Sha256Hash txHash;
//	private boolean isWithdrawn;
    
    public static CatenaStatement fromData(byte[] data) {
        CatenaStatement s = new CatenaStatement();
        s.data = data;
        return s;
    }
    
    public static CatenaStatement fromTxn(Transaction tx) {
        checkState(CatenaUtils.maybeCatenaTx(tx));
        
        CatenaStatement s = fromTxnHash(tx.getHash(), CatenaUtils.getCatenaTxData(tx));
//		s.updateWithdrawn(tx);
        return s;
    }
    
    public static CatenaStatement fromTxnHash(Sha256Hash txHash, byte[] data) {
        CatenaStatement stmt = new CatenaStatement();
        stmt.txHash = txHash;
        stmt.data = data;
//		stmt.isWithdrawn = false;
        
        return stmt;
    }
    
    private CatenaStatement() {}
    
    public Sha256Hash getTxHash() {
        if(txHash == null) {
            throw new IllegalStateException("You are accessing the tx hash of a data-only CatenaStatement object");
        }
        
        return txHash; 
    }
    
    public byte[] getData() { return data; }
    
    public boolean hasSameData(CatenaStatement s) { return Arrays.areEqual(data, s.data); }
    
    public static boolean hasSameData(Transaction a, Transaction b) {
        checkState(CatenaUtils.maybeCatenaTx(a));
        checkState(CatenaUtils.maybeCatenaTx(b));
        byte[] adata = CatenaUtils.getCatenaTxData(a);
        byte[] bdata = CatenaUtils.getCatenaTxData(b);
        return Arrays.areEqual(adata, bdata);
    }

    public static boolean hasSameData(byte[] adata, byte[] bdata) {
        return Arrays.areEqual(adata, bdata);
    }
    
    public String getAsString() {
        checkNotNull(data);
        return new String(data);
    }
    
//	public void updateWithdrawn(Wallet w) {
//		updateWithdrawn(w.getTransaction(txHash));
//	}
//	
//	public void updateWithdrawn(Transaction tx) {
//		isWithdrawn = (CatenaUtils.isBuildingTxn(tx) == false);
//	}
//	
//	/**
//	 * Returns true if statement was in the best chain but a fork happened and
//	 * now the TX is dead or in conflict.
//	 * 
//	 * @return
//	 */
//	public boolean isWithdrawn() { return isWithdrawn; } 
    
}
