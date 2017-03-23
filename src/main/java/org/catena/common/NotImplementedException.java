package org.catena.common;

public class NotImplementedException extends RuntimeException {

    private static final long serialVersionUID = -5900018733038623604L;
    
    public NotImplementedException() {}
    
    public NotImplementedException(String message) {
        super(message);
    }
}
