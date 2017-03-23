package org.catena.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.DumpedPrivateKey;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.MessageFormatter;

public class Utils {
    private static final Logger log = LoggerFactory.getLogger(Utils.class);
    
    static final byte[] HEX_CHAR_TABLE = {
        (byte)'0', (byte)'1', (byte)'2', (byte)'3',
        (byte)'4', (byte)'5', (byte)'6', (byte)'7',
        (byte)'8', (byte)'9', (byte)'a', (byte)'b',
        (byte)'c', (byte)'d', (byte)'e', (byte)'f'
    };

    public static String toHex(byte[] raw)
    {
        byte[] hex = new byte[2 * raw.length];
        int index = 0;

        for (byte b : raw) {
            int v = b & 0xFF;
            hex[index++] = HEX_CHAR_TABLE[v >>> 4];
            hex[index++] = HEX_CHAR_TABLE[v & 0xF];
        }

        return new String(hex);
    }
    
    public static Path getCurrentDirectory() 
    {
        return Paths.get(System.getProperty("user.dir"));
    }
    
    public static String readInputStream(InputStream is)
    {
        Scanner scanner = new Scanner(is);
        StringBuffer buf = new StringBuffer();
        while(scanner.hasNextLine())
            buf.append(scanner.nextLine() + "\n");
        scanner.close();
        return buf.toString();
    }
    
    public static Address readPublicKey(NetworkParameters params, String filePath) throws IOException 
    {
        log.debug("Reading a Bitcoin public key from '" + filePath + "':");
        String keyStr = new String(Files.readAllBytes(Paths.get(filePath))).trim()	;
        log.debug(keyStr);
        
        return Address.fromBase58(params, keyStr);
    }
    
    public static ECKey readPrivateKey(NetworkParameters params, String filePath) throws IOException 
    {
        log.debug("Reading a Bitcoin private key from '" + filePath + "':");
        String keyStr = new String(Files.readAllBytes(Paths.get(filePath))).trim()	;
        log.debug(keyStr);
        
        return DumpedPrivateKey.fromBase58(params, keyStr).getKey();
    }

    public static String fmt(String string, Object... objs) {
        return MessageFormatter.arrayFormat(string, objs).getMessage();
    }
    
    public static String[] concat(String[]... arrays) {
        int len = 0;
        for(String[] a : arrays) {
            len += a.length;
        }
        
        String[] result = new String[len];
        int i = 0;
        for(String[] a : arrays) {
            for(String s : a) {
                result[i] = s;
                i++;
            }
        }
        return result;
    }
}
