package org.catena.common;

//import static com.google.common.base.Preconditions.*;

import static org.junit.Assert.*;

import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.bitcoinj.core.DumpedPrivateKey;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.RegTestParams;
import org.catena.common.Utils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests that extends this class will always have a bitcoind instance started 
 * for them. Multiple bitcoind instances at the same time do not work because
 * they all listen on the same ports and changing those would require more
 * code engineering: new RegTestParams with different ports and an array of
 * bitcoind running instances in this class. 
 * 
 * Maven seems to only run a test after the previous one finishes, so there should 
 * never be multiple bitcoind instances at the same time. 
 */
public abstract class BitcoindRegtestTest extends SummarizedTest
{
    private static final Logger log = LoggerFactory.getLogger(BitcoindRegtestTest.class);
    
    protected static Path bitcoindDataDir;	// where bitcoind in regtest mode stores its files
    protected static Process bitcoindProc;
    
    protected static String GET_CATENA_FUNDS_CMD = "btc-scripts/get-catena-funds.sh";
    protected static String START_BITCOIND_CMD = "btc-scripts/start-bitcoind.sh";
    protected static String IS_BITCOIND_RUNNING_CMD = "btc-scripts/is-bitcoind-running.sh";
    protected static String KILL_BITCOIND_CMD = "btc-scripts/kill-bitcoind.sh";
    protected static String GENERATE_101_BLOCKS_CMD = "btc-scripts/gen-101-blocks.sh";
    
    protected static NetworkParameters params = RegTestParams.get();
    
    /**
     * Starts up a bitcoind regtest instance with data stored in a /tmp directory.
     * Initializes 101 blocks to make the 1st coinbase TX spendable.
     * 
     * @throws IOException
     * @throws InterruptedException
     */
    @BeforeClass
    public static void beforeClass() throws IOException, InterruptedException
    {
        log.debug("Setting up regtest environment: Starting bitcoind...");
        //log.debug("Current working directory: " + Utils.getCurrentDirectory());
        
        bitcoindDataDir = Files.createTempDirectory("regtest-bitcoind-");

        startBitcoind(bitcoindDataDir);
        assertBitcoindRunning(bitcoindDataDir);
    }
    
    /**
     * Shuts down bitcoind.
     * 
     * @throws IOException
     * @throws InterruptedException
     */
    @AfterClass
    public static void afterClass() throws IOException, InterruptedException
    {
        log.debug("Tearing down regtest environment: Killing bitcoind...");
        killBitcoind(bitcoindDataDir);
    }
    
    /**
     * By the time this returns, the bitcoind daemon should be ready to accept
     * JSON-RPC commands.
     * 
     * @param dataDir
     * @throws IOException
     * @throws InterruptedException
     */
    public static void startBitcoind(Path dataDir) throws IOException, InterruptedException {
        log.info("Storing bitcoind server data in: " + dataDir);
        // NOTE: Apparently if the process spits out a lot of output, then Java will
        // stop it until you read its output (or at least it seems like it).
        // So I left -printtoconsole off to avoid too much output.
        ProcessBuilder pb = new ProcessBuilder(START_BITCOIND_CMD, dataDir.toString());
        pb.redirectOutput(Redirect.INHERIT);
        pb.redirectError(Redirect.INHERIT);
        bitcoindProc = pb.start();
        
        try {
            int exitCode = bitcoindProc.exitValue();
            fail("Could not launch bitcoind, " + START_BITCOIND_CMD + " failed with code " + exitCode);
        } catch (IllegalThreadStateException e) {
            // this is expected, because the script does not exit until bitcoind is killed
        }

        log.debug("Initializing regtest blockchain...");
        Process cmd = Runtime.getRuntime().exec(GENERATE_101_BLOCKS_CMD);
        boolean success = cmd.waitFor() == 0;
        //log.debug(INIT_REGTEST_ENV_CMD + " output: " + Utils.readInputStream(cmd.getInputStream()));
        assertTrue("could not initialize bitcoind regtest blockchain data", success);
    }
    
    public static void killBitcoind(Path dataDir) throws IOException, InterruptedException {
        log.debug("Killing bitcoind regtest instance in: " + dataDir);
        Process cmd = Runtime.getRuntime().exec(KILL_BITCOIND_CMD + " " + dataDir.toString());
        boolean success = cmd.waitFor() == 0;

        assertTrue("could not kill bitcoind regtest instance", success);
        
        log.debug("Waiting for bitcoind to stop...");
        int code = bitcoindProc.waitFor();
        log.debug("bitcoind exited with code: " + code);
    }
    
    /**
     * Adds funds to a privat key and returns it.
     * 
     * @return
     * @throws InterruptedException
     * @throws IOException
     */
    public static ECKey generateRegtestFunds() throws InterruptedException, IOException {
        log.debug("Getting Catena funds from regtest mode...");
        Process cmd = Runtime.getRuntime().exec(GET_CATENA_FUNDS_CMD);
        boolean success = cmd.waitFor() == 0;
        
        String privateKey = Utils.readInputStream(cmd.getInputStream()).trim();
        log.debug(GET_CATENA_FUNDS_CMD + " output: " + privateKey);
        
        if(!success) {
            String stderr = Utils.readInputStream(cmd.getErrorStream());
            log.error(GET_CATENA_FUNDS_CMD + " stderr output: " + stderr);
        }
        assertTrue("could not get Catena funds", success);
        assertTrue("could not read private key from stdout of " + GET_CATENA_FUNDS_CMD, privateKey.length() > 0);

        try {
            return DumpedPrivateKey.fromBase58(params, privateKey).getKey();
        } catch(Exception e) {
            log.error("Could not parse private key from {} script: {}", GET_CATENA_FUNDS_CMD, privateKey);
            throw e;
        }
    }
    
    public static void assertBitcoindRunning(Path dataDir) throws IOException, InterruptedException {
        log.debug("Checking bitcoind regtest instance is running...");
        Process cmd = Runtime.getRuntime().exec(IS_BITCOIND_RUNNING_CMD + " " + Paths.get(dataDir.toString(), "regtest"));
        boolean success = cmd.waitFor() == 0;
        
        if(!success)
            log.warn(IS_BITCOIND_RUNNING_CMD + " script output: " + Utils.readInputStream(cmd.getInputStream())); 
        
        assertTrue("bitcoind regtest instance should have been running", success);
    }
    
    /**
     * Generates a block via the 'generate' JSON-RPC command.
     *  
     * @throws IOException
     * @throws InterruptedException
     */
    public void waitForBlock() throws IOException, InterruptedException
    {
        CatenaUtils.generateBlockRegtest();
    }
}
