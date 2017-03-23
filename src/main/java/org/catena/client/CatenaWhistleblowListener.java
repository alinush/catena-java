package org.catena.client;

import org.bitcoinj.core.Transaction;

public interface CatenaWhistleblowListener {
    
    public void onWhistleblow(Transaction tx, String message);
}
