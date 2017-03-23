package org.catena.common;

public class TestUtils {

    public static String[] generateStatements(int num, int start) {
        String stmts[] = new String[num];
        
         for(int i = 0; i < stmts.length; i++) {
             stmts[i] = "S" + (start + i);
         }
         
         return stmts;
    }

    public static String[] generateStatements(int num) {
        return generateStatements(num, 0);
    }

}
