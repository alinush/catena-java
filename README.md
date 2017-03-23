# Catena: Preventing Lies with Bitcoin

Catena can be used by any service or application who wants to prove a linear history of statements to its clients.

## How to run on Linux

First, install Maven and other dependencies:

    ./install-deps.sh

If you're on Mac OS X, download the Bitcoin source code from GitHub to `~/repos/bitcoin/` (you can change `set-env.sh` if you want to choose a different directory) and compile the `bitcoind` binary using instructions in `doc/build-osx.md`

Then, compile Catena:
    
    mvn compile

Then, setup your environment so that the Bitcoin-related scripts in `btc-scripts/` work:

    . set-env.sh

Then, run all the tests:

    ./test.sh

Or, run only [some of the tests](https://maven.apache.org/surefire/maven-surefire-plugin/examples/single-test.html):

    ./test.sh -Dtest=ReadChainTest
    ./test.sh -Dtest=WriteChainTest#testRestartReuseWallet

Then you can run a Catena server using `./run-server.sh` (pass in `-h` for help) and a Catena client using `./run-client.sh`.

## Eclipse project

You can edit the Catena source code using Eclipse Neon 4.6.0.
Project files are in this repository, you just have to import them in Eclipse.

## Bitcoin testnet

You can play with Catena on the Bitcoin testnet network for free:

 1. Get testnet Bitcoins from a free faucet like this one: https://tpfaucet.appspot.com/
 2. Start the server and create a root-of-trust TXN ([example here](https://www.blocktrail.com/tBTC/tx/422731728d40684ad19fcfd37f6a78dbb5a824f6c61ab21ed1293b6f4ee5a4b5))
 3. Use the server to create a config for the Catena client
 4. Launch the client with the config file and wait for the statements to arrive

Some websites:

 - [Blocktrail.com](https://www.blocktrail.com/), for browsing the testnet blockchain
   - Also, [blockexplorer.com](https://testnet.blockexplorer.com/), for browsing the testnet blockchain
 - [Testnet faucet](https://testnet.manu.backend.hamburg/faucet), for getting coins

