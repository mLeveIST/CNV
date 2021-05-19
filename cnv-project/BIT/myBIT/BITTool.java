package BIT.myBIT;

import BIT.highBIT.*;
import java.io.*;
import java.util.*;


public class BITTool {

  private static final String fileSeparator = System.getProperty("file.separator");
  private static final String iPath = "pt/ulisboa/tecnico/cnv/server/WebServer";

  private static final int I_MASK = 64;
  private static final int BB_MASK = 32;
  private static final int M_MASK = 16;
  private static final int FL_MASK = 8;
  private static final int FS_MASK = 4;
  private static final int L_MASK = 2;
  private static final int S_MASK = 1;


  public static void main(String[] args) {
    String fileNames[];

    if (args.length < 3) {
      System.out.println("Syntax: java BITTool icode in_path out_path");
      System.exit(-1);
    }

    fileNames = (new File(args[1])).list();

    for (int i = 0; i < fileNames.length; i++) {
      String fileName = fileNames[i];

      if (fileName.endsWith(".class")) {
        instrumentClass(fileName, Integer.parseInt(args[0]), args[1], args[2]);
      }
    }
  }


  private static void instrumentClass(String fileName, int iCode, String classPath, String outputPath) {
    ClassInfo classInfo = new ClassInfo(classPath + fileSeparator + fileName);

    for (Enumeration routines = classInfo.getRoutines().elements(); routines.hasMoreElements(); ) {
      Routine routine = (Routine) routines.nextElement();

      if (useMetric(iCode, M_MASK))
        doCountMethods(routine);

      for (Enumeration basicBlocks = routine.getBasicBlocks().elements(); basicBlocks.hasMoreElements(); ) {
        BasicBlock basicBlock = (BasicBlock) basicBlocks.nextElement();

        if (useMetric(iCode, BB_MASK))
          doCountBasicBlocks(basicBlock);
        if (useMetric(iCode, I_MASK))
          doCountInstructions(basicBlock);
      }

      for (Enumeration instructions = (routine.getInstructionArray()).elements(); instructions.hasMoreElements(); ) {
        Instruction instruction = (Instruction) instructions.nextElement();
        int opcode = instruction.getOpcode();

        if (useMetric(iCode, FL_MASK) && opcode == InstructionTable.getfield)
          doCountFieldLoads(instruction);
        else if (useMetric(iCode, FS_MASK) && opcode == InstructionTable.putfield)
          doCountFieldStores(instruction);
        else {
          short instructionType = InstructionTable.InstructionTypeTable[opcode];

          if (useMetric(iCode, L_MASK) && instructionType == InstructionTable.LOAD_INSTRUCTION)
            doCountLoads(instruction);
          else if (useMetric(iCode, S_MASK) && instructionType == InstructionTable.STORE_INSTRUCTION)
            doCountStores(instruction);
        }
      }
    }

    classInfo.write(outputPath + fileSeparator + fileName);
  }

  private static boolean useMetric(int iCode, int mask) {
    return (iCode & mask) != 0;
  }

  private static void doCountMethods(Routine routine) {
    routine.addBefore(iPath, "countMethods", new Integer(1));
  }

  private static void doCountBasicBlocks(BasicBlock basicBlock) {
    basicBlock.addBefore(iPath, "countBasicBlocks", new Integer(1));
  }

  private static void doCountInstructions(BasicBlock basicBlock) {
    basicBlock.addBefore(iPath, "countInstructions", new Integer(basicBlock.size()));
  }

  private static void doCountFieldLoads(Instruction instruction) {
    instruction.addBefore(iPath, "countFieldLoads", new Integer(1));
  }

  private static void doCountFieldStores(Instruction instruction) {
    instruction.addBefore(iPath, "countFieldStores", new Integer(1));
  }

  private static void doCountLoads(Instruction instruction) {
    instruction.addBefore(iPath, "countLoads", new Integer(1));
  }

  private static void doCountStores(Instruction instruction) {
    instruction.addBefore(iPath, "countStores", new Integer(1));
  }
}
