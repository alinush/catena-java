package org.catena.common;

import java.io.IOException;

import org.bitcoinj.core.NetworkParameters;
import org.catena.client.CatenaClient;

public interface AbstractClientFactory {
    
    public CatenaClient create(NetworkParameters params) throws IOException;
}
