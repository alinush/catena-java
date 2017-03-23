package org.catena.common;

import org.bitcoinj.core.VersionMessage;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

public class SummarizedTest {
    private boolean someTestsFailed;

    @Rule
    public TestWatcher watchman = new TestWatcher() {

        @Override
        protected void starting(final Description description) {
            
            String methodName = description.getMethodName();
            String className = description.getClassName();
            className = className.substring(className.lastIndexOf('.') + 1);
            System.out.println("\n * Running " + className + "::" + methodName);
        }

        @Override
        protected void failed(Throwable e, Description desc) {
            someTestsFailed = true;
        }
        
        @Override
        protected void succeeded(Description desc) {
        	System.out.print("Unit test " + desc.getDisplayName() + " succeeded!");
        }
    };
    
    @ClassRule
    public static TestWatcher versionWatcher = new TestWatcher() {
        @Override 
        protected void finished(Description description) {
            System.out.println("\nLibrary versions: Ran " + description.getClassName() + " tests with "
                    + "bitcoinj " + VersionMessage.BITCOINJ_VERSION
                    + "\n");
        }
    };


    protected boolean someTestsFailed() {
        return someTestsFailed;
    }
}
