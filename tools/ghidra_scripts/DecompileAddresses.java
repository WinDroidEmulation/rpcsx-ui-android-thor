// Decompile specific addresses from the currently opened Ghidra program.
// Usage:
//   -postScript DecompileAddresses.java C:\out.txt 0x00cc948c 0x00cc945c

import java.io.File;
import java.io.PrintWriter;

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.symbol.Reference;

public class DecompileAddresses extends GhidraScript {
    @Override
    protected void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 2) {
            println("Usage: DecompileAddresses.java <out-file> <address> [address...]");
            return;
        }

        File outFile = new File(args[0]);
        File parent = outFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        DecompInterface decomp = new DecompInterface();
        decomp.openProgram(currentProgram);
        decomp.setSimplificationStyle("decompile");

        try (PrintWriter out = new PrintWriter(outFile, "UTF-8")) {
            out.println("Program: " + currentProgram.getName());
            out.println("Language: " + currentProgram.getLanguageID());
            out.println();

            for (int i = 1; i < args.length; i++) {
                Address address = currentProgram.getAddressFactory()
                    .getDefaultAddressSpace()
                    .getAddress(args[i]);

                out.println("==== " + args[i] + " ====");
                dumpNearbyInstructions(out, address);
                dumpReferences(out, address);

                Function function = getFunctionContaining(address);
                if (function == null) {
                    function = getFunctionAt(address);
                }

                if (function == null) {
                    try {
                        createFunction(address, "thor_probe_" + address.toString());
                        function = getFunctionAt(address);
                    } catch (Exception e) {
                        out.println("Function creation failed: " + e.getMessage());
                    }
                }

                if (function == null) {
                    out.println("No function found at/containing " + args[i]);
                    out.println();
                    continue;
                }

                out.println("Function: " + function.getName() + " @ " + function.getEntryPoint());
                DecompileResults results = decomp.decompileFunction(function, 90, monitor);
                if (!results.decompileCompleted()) {
                    out.println("Decompile failed: " + results.getErrorMessage());
                } else {
                    out.println(results.getDecompiledFunction().getC());
                }
                out.println();
            }
        } finally {
            decomp.dispose();
        }

        println("Wrote " + outFile.getAbsolutePath());
    }

    private void dumpNearbyInstructions(PrintWriter out, Address address) {
        out.println("Instructions near " + address + ":");
        try {
            Address start = address.subtract(0x40);
            InstructionIterator it = currentProgram.getListing().getInstructions(start, true);
            int count = 0;
            while (it.hasNext() && count < 48) {
                Instruction instruction = it.next();
                out.println("  " + instruction.getAddress() + ": " + instruction);
                count++;
            }
        } catch (Exception e) {
            out.println("  instruction dump failed: " + e.getMessage());
        }
        out.println();
    }

    private void dumpReferences(PrintWriter out, Address address) {
        out.println("References from " + address + ":");
        int fromCount = 0;
        for (Reference ref : currentProgram.getReferenceManager().getReferencesFrom(address)) {
            out.println("  -> " + ref.getToAddress() + " " + ref.getReferenceType());
            fromCount++;
        }
        if (fromCount == 0) {
            out.println("  <none>");
        }

        out.println("References to " + address + ":");
        int toCount = 0;
        for (Reference ref : currentProgram.getReferenceManager().getReferencesTo(address)) {
            out.println("  <- " + ref.getFromAddress() + " " + ref.getReferenceType());
            toCount++;
        }
        if (toCount == 0) {
            out.println("  <none>");
        }
        out.println();
    }
}
