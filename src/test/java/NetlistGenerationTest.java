import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import GerberFileParser.Attribute;
import GerberFileParser.AttributeDictionary;
import GerberFileParser.GerberFileParser;
import GerberFileParser.GraphicalObject;

public class NetlistGenerationTest {

    private Exception savedError = null;
    
    /**
     * This test extracts the net list from a Gerber file and compares it to the net list generated 
     * directly by KiCad to ensure the net lists are the same.
     * 
     * @throws Exception - if there is a problem either reading the files or if the net lists 
     * miscompare.
     */
    @Test
    public void testNetListGeneration() throws Exception {
        File testFileDir = new File(ClassLoader.getSystemResource("gerberFiles").getPath());
        File expectedDir = new File(ClassLoader.getSystemResource("expectedResults").getPath());
        
        //This particular board design only has components on the top side so we only need to
        //examine the top copper layer to obtain all the net list information. If the board also had
        //components on the bottom side or embedded in inner layers, those layers would also need
        //to be examined as well to obtain a complete net list.
        File gerberFile = new File(testFileDir.getPath() + File.separator + "boardDesign" + 
                File.separator + "IR2IP-F_Cu.gbr" );
        
        //The expected net list was generated by KiCad and exported in CadStar format
        File expectedFile = new File(expectedDir.getPath() + File.separator + "boardDesign" + 
                File.separator + "IR2IP.frp" );
        
        //Parse the Gerber file in the background
        GerberFileParser parser = new GerberFileParser(gerberFile);
        System.out.println("Parsing of Gerber file started...");
        parser.parseFileInBackground(null, null, (ex)->savedError=ex);
        
        //Read in the expected net list
        System.out.println("Reading expected net list...");
        List<String> expectedNetList = readExpectedNetList(expectedFile);
        System.out.println("Reading of expected net list completed.");
        
        //Wait for the parser to finish
        while (!parser.isDone() && !parser.isError()) {
            //spin until the parser is done (or errors out)
        }
        
        if (parser.isError()) {
            throw new Exception(savedError.getMessage());
        }
        System.out.println("Parsing of Gerber file completed successfully.");
        
        //Extract the Gerber net list
        System.out.println("Extracting Gerber net list...");
        Map<String, Integer> pinCounts = new HashMap<>();
        List<String> gerberNetList = new ArrayList<>();
        goLoop: for (GraphicalObject go : parser.getImageGraphicStream().getStream()) {
            AttributeDictionary goAttributes = go.getAttributes();
            Attribute pinAttribute = goAttributes.get(".P");
            if (pinAttribute == null) {
                continue;
            }
            Attribute netAttribute = goAttributes.get(".N");
            if (netAttribute == null || netAttribute.getValues().size() == 0 ||
                    netAttribute.getValues().get(0).equals("N/C")) {
                continue;
            }
            
            //The KiCad Gerber exporter exports inverted signal names with a tilde followed by the 
            //signal name enclosed in curly braces ~{<signal name>}. However, KiCad seems to drop 
            //the curly braces when it exports in other formats so we remove the curly braces here
            //to ensure the signal names are in the same format.
            String netName = netAttribute.getValues().get(0).replaceAll("[{}]", "");
            
            //Build a net terminal as a string "<net name>,<ref des>,<pin>"
            String netTerminal = netName + "," +
                    pinAttribute.getValues().get(0) + "," +
                    pinAttribute.getValues().get(1);

            //Skip any duplicates
            for (String existingNetTerminal : gerberNetList) {
                if (netTerminal.equals(existingNetTerminal)) {
                    continue goLoop;
                }
            }
            
            gerberNetList.add(netTerminal);
            
            //Keep track of how many pins are in each net
            if (pinCounts.containsKey(netName)) {
                pinCounts.put(netName, pinCounts.get(netName)+1);
            }
            else {
                pinCounts.put(netName, 1);
            }
        }
        Collections.sort(gerberNetList);
        
        //Remove any single pin nets (the expected net list does not include single pin nets)
        List<String> gerberNetlistCopy = new ArrayList<>(gerberNetList);
        for (String netTerminal : gerberNetlistCopy) {
            int idx = netTerminal.indexOf(",");
            String netName = netTerminal.substring(0, idx);
            if (pinCounts.get(netName) == 1) {
                gerberNetList.remove(netTerminal);
                pinCounts.put(netName, 0);
            }
        }
        System.out.println("Extraction of Gerber net list completed.");
        
        //Make sure both net lists are the same size
        System.out.println("Comparing Gerber net list with expected net list...");
        if (gerberNetList.size() != expectedNetList.size()) {
            throw new Exception("The Gerber net list is not the same size as the expected net list.");
        }
        
        //Check each net terminal to make sure they are the same
        int i = 0;
        for(String netTerminal : gerberNetList) {
            if (!netTerminal.equals(expectedNetList.get(i))) {
                throw new Exception("Net list miscompare: " + netTerminal + "  <->  " + expectedNetList.get(i));
            }
            i++;
        }
        
        System.out.println("All " + gerberNetList.size() + " net terminals compared successfully.");
    }

    /**
     * Reads a CadStar netlist file generated by KiCad
     * @param expectedFile - the file to read
     * @return the sorted netlist
     * @throws FileNotFoundException
     * @throws IOException
     */
    private List<String> readExpectedNetList(File expectedFile) throws FileNotFoundException, IOException {
        List<String> ret = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(expectedFile))) {
            String line = reader.readLine();
            String netName = "";
            boolean maybeMore = false;
            while (line != null) {
                if (line.startsWith(".ADD_TER")) {
                    line = line.substring(8).strip();
                    int idx = line.indexOf(" ");
                    String refDes = line.substring(0, idx);
                    line = line.substring(idx).strip();
                    idx =  line.indexOf(" ");
                    String pin = line.substring(0, idx);
                    netName = line.substring(idx).strip();
                    netName = netName.substring(1, netName.length()-1); //remove quotes
                    ret.add(netName + "," + refDes + "," + pin);
                    maybeMore = false;
                }
                else if (line.startsWith(".TER")) {
                    line = line.substring(4).strip();
                    int idx = line.indexOf(" ");
                    String refDes = line.substring(0, idx);
                    String pin = line.substring(idx).strip();
                    ret.add(netName + "," + refDes + "," + pin);
                    maybeMore = true;
                }
                else if (maybeMore && !line.isBlank()) {
                    line = line.strip();
                    int idx = line.indexOf(" ");
                    String refDes = line.substring(0, idx);
                    String pin = line.substring(idx).strip();
                    ret.add(netName + "," + refDes + "," + pin);
                }
                else if (line.isBlank()) {
                    maybeMore = false;
                }
                line = reader.readLine();
            }
        }
        Collections.sort(ret);
        
        return ret;
    }
    

}