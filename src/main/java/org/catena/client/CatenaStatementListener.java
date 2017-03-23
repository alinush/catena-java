package org.catena.client;

import org.catena.common.CatenaStatement;

public interface CatenaStatementListener {

    public void onStatementAppended(CatenaStatement s);
    
    public void onStatementWithdrawn(CatenaStatement s);
}
