package BIT.myBIT;

import BIT.highBIT.*;
import java.io.*;
import java.util.*;


public class BITTool {

  private static final String fileSeparator = System.getProperty("file.separator");
  private static final String iPath = "pt/ulisboa/tecnico/cnv/server/WebServer";


  public static void main(String[] args) {
    String fileNames[] = (new File(args[0])).list();

    for (int i = 0; i < fileNames.length; i++) {
      String fileName = fileNames[i];

      if (fileName.endsWith(".class")) {
        instrumentClass(fileName, args[0], args[1]);
      }
    }
  }


  private static void instrumentClass(String fileName, String classPath, String outputPath) {
    ClassInfo classInfo = new ClassInfo(classPath + fileSeparator + fileName);

    for (Enumeration routines = classInfo.getRoutines().elements(); routines.hasMoreElements(); ) {
      Routine routine = (Routine) routines.nextElement();

      routine.addBefore(iPath, "countMethods", new Integer(1));

      for (Enumeration basicBlocks = routine.getBasicBlocks().elements(); basicBlocks.hasMoreElements(); ) {
        BasicBlock basicBlock = (BasicBlock) basicBlocks.nextElement();
        basicBlock.addBefore(iPath, "countInstructions", new Integer(basicBlock.size()));
      }
    }

    classInfo.write(outputPath + fileSeparator + fileName);
  }
}
