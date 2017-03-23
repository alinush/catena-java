package org.catena.common;

import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Scanner;

import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Throwables;

public class CatenaApp {
    private static final Logger log = LoggerFactory.getLogger(CatenaApp.class);
            
    protected static NetworkParameters params;
    protected static String btcnet;
    protected static String directory;
    protected static SimpleWallet wallet;
    protected static CatenaWalletExtension ext;
    protected static boolean isRegtestEnv = false;
    protected static CatenaService svc;
    
    protected static void connectAndStart(Runnable cmdlineUi) {
        if (params == RegTestParams.get()) {
            // Regression test mode, so there's no public network for it: you're
            // expected to be running a local "bitcoind -regtest" instance.
            svc.connectToLocalHost();
        }

        // Download the block chain and wait until it's done.
        svc.startAsync();
        svc.awaitRunning();
        
        wallet = svc.getCatenaWallet();
        ext = wallet.getCatenaExtension();
        
        cmdlineUi.run();

        // For some reason, though the service stops, the ServerApp thread is left hanging and cannot be killed by Maven.
        // Not sure why, but replicated this with just WalletAppKit, to make sure it's not CatenaServer's fault.
        System.out.print("Stopping Catena service... ");
        svc.stopAsync();
        System.out.println("done.");
        System.out.print("Waiting for Catena service to stop... ");
        svc.awaitTerminated();
        System.out.println("done.");
    }

    /**
     * Parses the bitcoin network that we should connect to from the command line arguments array.
     *   
     * @param args
     * @param pos the index in the array where the bitcoin network is stored
     */
    protected static void parseBitcoinNetwork(String[] args, int pos, String dirSuffix) {
        assert args.length > pos;
        
        btcnet = args[pos];
        if(btcnet.equals("testnet")) {
            directory = "testnet";
            params = TestNet3Params.get();
        } else if (btcnet.equals("mainnet")) {
            params = MainNetParams.get();
            directory = "mainnet";
        } else if (btcnet.equals("regtest")) {
            params = RegTestParams.get();
            directory = "regtest";
            isRegtestEnv = true;
        } else {
            System.err.println("Invalid Bitcoin network specified: " + btcnet);
            System.err.println("Please use one of mainnet, testnet or regtest");
            System.exit(1);
            return;
        }
        
        directory += dirSuffix;
    }

    protected static void maybeParseDataDirectory() {
        try {
            Paths.get(directory);
        } catch(InvalidPathException e) {
            System.err.println(Utils.fmt("Error parsing path '{}': {}.\n", directory, e.getMessage()));
            System.err.println("Stack trace from exception: " + Throwables.getStackTraceAsString(e));
            System.exit(1);
        }
    }

    protected static void listStatementsHandler(Scanner scanner, boolean isFwd) {
        int num;
        while(true) {
            try {
                System.out.print("Please enter the number of statements you'd like printed: ");
                num = Integer.parseInt(scanner.nextLine());
                if(num < 0) {
                    num = -1;
                }
                break;
            } catch(NumberFormatException e) {
                System.out.println("\nERROR: You must enter a number! Try again...");
            }
        }
        
        listStatements(isFwd, num);
    }

    protected static void listStatements(boolean isFwd, int numPrint) {
        int numStmts = wallet.getNumStatements();
        Iterator<CatenaStatement> it = wallet.statementIterator(isFwd);
        
        int c = isFwd ? 1 : numStmts;
        for(int i = 0; i < numPrint; i++) {
            if(it.hasNext()) {
                CatenaStatement s = it.next();
                Transaction prevTx = CatenaUtils.getPrevCatenaTx(wallet, s.getTxHash());
                System.out.printf("Statement #%d: %s (tx %s, prev %s)\n", c, s.getAsString(), s.getTxHash(), prevTx.getHash());
                
                c = isFwd ? c + 1 : c - 1;
            }
        }
    }

    protected static void printConfigHandler() {
        printConfig();
    }

    protected static void printConfig() {
        System.out.println("Root-of-trust TXID: " + (ext.hasRootOfTrustTxid() ? ext.getRootOfTrustTxid() : "none yet"));
        System.out.println(" + Chain name: " + (ext.hasName() ? ext.getName() : "none yet"));
        System.out.println(" + Chain address: " + wallet.getChainAddress());
        System.out.println("# of statements issued so far: " + wallet.getNumStatements());
        System.out.println("# of blocks in blockchain: " + svc.chain().getBestChainHeight());
        System.out.println("Wallet balance: " + wallet.getBalance().toFriendlyString());
        
        Iterator<CatenaStatement> it = wallet.statementIterator(false);
        if(it.hasNext()) {
            CatenaStatement lastStmt = it.next();
            System.out.println("Last issued statement: " + lastStmt.getAsString());
        } else {
            System.out.println("Last issued statement: none yet");
        }
    }

    protected static void errorHandler(Throwable e) {
        if(e.getMessage() != null) {
            System.out.println("An " + e.getClass().getSimpleName() + " exception occurred: " + e.getMessage());
        } else {
            System.out.println("An " + e.getClass().getSimpleName() + " exception without a message occurred");
        }
        log.debug("Stack trace: " + Throwables.getStackTraceAsString(e));
        System.out.println("Please try again.");
    }
}
