package org.catena.client;

import org.bitcoinj.core.*;

import org.catena.common.CatenaApp;
import org.catena.common.Utils;

import com.google.common.base.Throwables;

import java.io.File;
import java.util.Scanner;

public class ClientApp extends CatenaApp {
    private static CatenaClient client;
    private static Sha256Hash txid;
    private static Address chainAddr;
    
    public static void main(String[] args) throws Exception {
        // This line makes the log output more compact and easily read, especially when using the JDK log adapter.
        //BriefLogFormatter.init();
        
        if (args.length < 3) {
            System.err.println("Usage: <root-of-trust-txid> <expected-chain-addr> mainnet|testnet|regtest");
            return;
        }

        // Parse the root-of-trust TXID
        String txidHex = args[0];        
        try {
            txid = Sha256Hash.wrap(txidHex);
        } catch (Exception e) {
            System.err.println(Utils.fmt("Error decoding root-of-trust TXID hash '{}': {}.\n", txidHex, e.getMessage()));
            System.err.println("Stack trace from exception: " + Throwables.getStackTraceAsString(e));
            System.exit(1);
            return;
        }

        // Parse the bitcoin network we should connect to
        parseBitcoinNetwork(args, 2, "-client");
        if(args.length > 3) {
            directory = args[3];
        }
        
        maybeParseDataDirectory();
        
        // Parse the Catena chain address
        String addr = args[1];
        try {
            chainAddr = Address.fromBase58(params, addr);
        } catch(Exception e) {
            System.err.println(Utils.fmt("Error decoding Bitcoind address '{}': {}.\n", addr, e.getMessage()));
            System.err.println("Stack trace from exception: " + Throwables.getStackTraceAsString(e));
            System.exit(1);
            return;
        }

        // Client can tell if it needs the chainAddr and txid or not, by looking in its wallet
        client = new CatenaClient(params, new File(directory), txid, chainAddr, null);
        svc = client;
        
        connectAndStart(new Runnable() {
            @Override
            public void run() {
                cmdlineUi();
            }
        });
    }

    private static void cmdlineUi() {
        final int LIST_FIRST = 1, LIST_LAST = 2, PRINT_CONFIG = 3, EXIT = 4;
        
        Scanner scanner = new Scanner(System.in);
        
        uiloop: while(true) {
            System.out.println("\nCatena client commands: ");
            System.out.println("1. List first statements");
            System.out.println("2. List last statements");
            System.out.println("3. Print Catena client config and stats");
            System.out.println("4. Exit");
            System.out.println("");
            System.out.print("Please enter your command (from 1-4): ");
            
            int opt;
            try {
                String line = scanner.nextLine();
                if(line.equalsIgnoreCase("q") || line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                    break;
                }
                opt = Integer.parseInt(line);
            } catch(NumberFormatException e) {
                System.out.println("\nERROR: You must enter a number between 1 and 4! Try again...");
                continue;
            }
            
            System.out.println();
            
            try {
            switch(opt) {
                case LIST_FIRST:
                    listStatementsHandler(scanner, true);
                    break;
                case LIST_LAST:
                    listStatementsHandler(scanner, false);
                    break;
                case PRINT_CONFIG:
                    printConfigHandler();
                    break;
                case EXIT:
                    break uiloop;
                default:
                    System.out.println("\nERROR: You must enter a number between 1 and 4! Try again...");
                    break;
                }
            } catch(Throwable e) {
                errorHandler(e);
            }
        }
        
        scanner.close();
    }
}