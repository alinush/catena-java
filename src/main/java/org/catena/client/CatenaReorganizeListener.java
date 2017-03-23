package org.catena.client;

import java.math.BigInteger;

public interface CatenaReorganizeListener {
    
    public void onReorganize(int oldNumBlocks, int newNumBlocks, BigInteger oldChainWork, BigInteger newChainWork);
}
