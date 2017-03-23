package org.catena.server;

import org.bitcoinj.core.*;
import org.catena.common.CatenaApp;
import org.catena.common.CatenaUtils;
import org.catena.common.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Throwables;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

public class ServerApp extends CatenaApp {
    
    private static final Logger log = LoggerFactory.getLogger(ServerApp.class);
    private static ECKey chainKey;
    private static CatenaServer server;
    private static Scanner scanner;
    
    public static void main(String[] args) throws Exception {
        //BriefLogFormatter.init();
        //BriefLogFormatter.initVerbose();
        //BriefLogFormatter.initWithSilentBitcoinJ();

        if (args.length < 2) {
            // TODO: handle root-of-trust txid for new wallets with reused keys 
            System.err.println("Usage: <chain-secret-key> mainnet|testnet|regtest [<datadir>] [<root-of-trust-txid>]");
            return;
        }
        
        // Parse the Bitcoin network we're connecting to
        parseBitcoinNetwork(args, 1, "-server");
        if(args.length > 2) {
            directory = args[2];
        }
        
        maybeParseDataDirectory();
        
        // Read the chain's secret key from the file (we do this *after* we've set up the NetworkParameters)
        String chainKeyStr = args[0];
        try {
            chainKey = DumpedPrivateKey.fromBase58(params, chainKeyStr).getKey(); 
        } catch(Exception e) {
            System.err.println(Utils.fmt("Error decoding Bitcoin secret key '{}': {}.\n", chainKeyStr, e.getMessage()));
            System.err.println("Stack trace from exception: " + Throwables.getStackTraceAsString(e));
            System.exit(1);
            return;
        }
        
        // Server can tell if it needs the chainKey or not, by looking in its wallet
        server = new CatenaServer(params, new File(directory), chainKey, null);
        svc = server;
        //WalletAppKit server = new WalletAppKit(params, new File(directory), "-server");

        connectAndStart(new Runnable() {
            @Override
            public void run() {
                cmdlineUi();
            }
        });
    }

    private static void cmdlineUi() {
        final int ISSUE = 1, LIST_FIRST = 2, LIST_LAST = 3, PRINT_CONFIG = 4, PRINT_PRIVKEY = 5, 
                GEN_CLIENT_CONFIG = 6, EXIT = 7;
        
        scanner = new Scanner(System.in);
        
        uiloop: while(true) {
            System.out.println("\nCatena server commands: ");
            System.out.println("1. Issue statement");
            System.out.println("2. List first statements");
            System.out.println("3. List last statements");
            System.out.println("4. Print Catena server config and stats (no private key)");
            System.out.println("5. Print private key");
            System.out.println("6. Generate Catena client config file");
            System.out.println("7. Exit");
            System.out.println();
            System.out.print("Please enter your command (from 1-7): ");
            
            int opt;
            try {
                String line = scanner.nextLine();
                if(line.equalsIgnoreCase("q") || line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                    break;
                }
                opt = Integer.parseInt(line);
            } catch(NumberFormatException e) {
                System.out.println("\nERROR: You must enter a number between 1 and 7! Try again...");
                continue;
            }
            
            System.out.println();
            
            try {
                switch(opt) {
                case ISSUE:
                    issueStatementHandler();
                    break;
                case LIST_FIRST:
                    listStatementsHandler(scanner, true);
                    break;
                case LIST_LAST:
                    listStatementsHandler(scanner, false);
                    break;
                case PRINT_CONFIG:
                    printConfigHandler();
                    break;
                case PRINT_PRIVKEY:
                    printPrivKeyHandler();
                    break;
                case GEN_CLIENT_CONFIG:
                    genClientConfigFileHandler();
                    break;
                case EXIT:
                    break uiloop;
                default:
                    System.out.println("ERROR: You must enter a number between 1 and 7! Try again...");
                    break;
                }
            } catch (Throwable e) {
                errorHandler(e);
            }
        }
        
        scanner.close();
    }

    private static void issueStatementHandler() throws InsufficientMoneyException, IOException, InterruptedException {
        // If there's no root-of-trust TXN, we gotta take care of that first
        if(ext.hasRootOfTrustTxid() == false) {
            issueRootOfTrustHandler();
            return;
        } else {
            log.debug("Already have root-of-trust txid '{}'", ext.getRootOfTrustTxid());
        }
        
        System.out.print("Please type in your next issued statement: ");
        String stmt = scanner.nextLine();
        if(stmt.trim().isEmpty()) {
            System.out.println("Cancelled due to empty statement. Please try again but type something in.");
            return;
        }
        
        issueStatement(stmt);
    }

    private static void issueStatement(String stmt)
            throws InsufficientMoneyException, IOException, InterruptedException {
        Transaction txn = appendStatement(stmt);
        
        Transaction prevTx = CatenaUtils.getPrevCatenaTx(wallet, txn.getHash());
        
        System.out.printf("Created tx '%s' with statement '%s' (prev tx is '%s')\n", txn.getHash(), stmt, prevTx.getHash());
    }

    private static Transaction appendStatement(String stmt) throws InsufficientMoneyException, IOException, 
        InterruptedException {
        Transaction txn = server.appendStatement(stmt.getBytes());
        if(isRegtestEnv) {
            // We generate the block ourselves if we are in regtest mode
            CatenaUtils.generateBlockRegtest();
        }
        return txn;
    }

    private static void issueRootOfTrustHandler() throws InsufficientMoneyException, IOException, InterruptedException {
        Address chainAddr = wallet.getChainAddress();
        System.out.printf("It looks like there is no Catena chain associated with the %s address from your wallet\n",
                chainAddr);
        
        System.out.printf("If you'd like to start a new Catena chain for the %s address, please type in its name: ", chainAddr);
        String chainName = scanner.nextLine();
        
        issueRootOfTrust(chainName);
    }

    private static void issueRootOfTrust(String chainName) throws InsufficientMoneyException, IOException, 
        InterruptedException {
        Transaction txn = appendStatement(chainName);
        
        System.out.printf("Created root-of-trust tx '%s' for chain '%s'\n", txn.getHash(), chainName);
    }

    private static void printPrivKeyHandler() {
        printPrivKey();
    }

    private static void printPrivKey() {
        System.out.println("Catena server's chain private key: " + wallet.getChainKey().getPrivateKeyAsWiF(params));
    }
    
    private static void genClientConfigFileHandler() throws IOException {
        Path path;
        while(true) {
            try {
                System.out.print("Please enter the directory where you'd like the config.ini file to be saved (leave blank for 'catena-client/'): ");
                String dirStr = scanner.nextLine();
                
                if(dirStr.trim().isEmpty()) {
                    dirStr = "catena-client";
                }
                Path dir = Paths.get(dirStr);
                path = Paths.get(dir.toString(), "config.ini");
                
                if(path.toFile().exists() == false) {
                    if(dir.toFile().isFile()) {
                        System.out.printf("The %s directory you entered is actually a file, not a directory. Try again.\n", dir);
                        continue;
                    }
                    
                    if(dir.toFile().exists() == false)
                        Files.createDirectory(dir);
                    
                    break;
                } else {
                    System.out.printf("ERROR: We won't overwrite the already existing file at %s. Please pick a " + 
                            "different directory where the config.ini file doesn't exist.\n", path);
                }
            } catch(InvalidPathException e) {
                System.out.println("\nERROR: You must enter a valid path! Try again...");
            }
        }
        
        genClientConfigFile(path);
    }

    private static void genClientConfigFile(Path path) throws IOException {
        if(ext.hasRootOfTrustTxid()) {
            FileWriter writer = new FileWriter(path.toFile());
            writer.write(Utils.fmt("pubkey={}\n", wallet.getChainAddress()));
            writer.write(Utils.fmt("txid={}\n", ext.getRootOfTrustTxid()));
            writer.write(Utils.fmt("btc_env={}\n", btcnet)); 
            writer.close();
            System.out.printf("\nCatena client's %s file was written successfully!\n", path);
        } else {
            System.out.println("\nERROR: The root-of-trust TXN hasn't been created yet. Please issue your first statement!");
        }        
    }
}