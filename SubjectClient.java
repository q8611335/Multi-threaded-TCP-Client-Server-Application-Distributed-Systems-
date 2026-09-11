import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.util.Map;

/**
 * SubjectClient - starter skeleton.
 *
 * WHAT'S ALREADY DONE FOR YOU:
 *   - Command-line argument parsing.
 *   - The interactive command loop: reading a line from standard input,
 *     splitting it into tokens, checking the argument count for each
 *     command, and printing a usage message on bad input - all without
 *     contacting the server, per the spec.
 *
 * WHAT YOU NEED TO IMPLEMENT (see the TODOs below):
 *   - Opening a socket to the server.
 *   - In each handleXxx method: building the right ProtocolMessage
 *     request, sending it as one JSON line, reading back one line of
 *     response, parsing it with ProtocolMessage.parse(...), and printing
 *     a human-readable summary. The exact wording you print is up to you.
 *   - Closing the connection cleanly on "quit".
 *
 * Usage:
 *   java -jar SubjectClient.jar <server-address> <server-port>
 */
public class SubjectClient {
    //// outside of main() for all handleXxx methods to use
    private static Socket clientSocket; 
    private static BufferedReader serverReader;
    private static BufferedWriter serverWriter;
    
    public static void main(String[] args) {

        // ==================== Argument parsing (provided) ====================
        if (args.length != 2) {
            System.err.println("Usage: java -jar SubjectClient.jar <server-address> <server-port>");
            System.exit(1);
            return;
        }

        String serverAddress = args[0];
        int serverPort = -1;
        try {
            serverPort = Integer.parseInt(args[1]);
            if (serverPort < 0 || serverPort > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            System.err.println("Invalid port '" + args[1] + "': must be an integer between 0 and 65535.");
            System.exit(1);
            return;
        }

        // step 7 === Open a socket to the server, and create reader/writer for it. ====================
        clientSocket = null;
        try {
            clientSocket = new Socket(serverAddress, serverPort);
            serverReader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            serverWriter = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream()));
        } catch (IOException e) {
            System.err.println("Could not connect to server: " + e.getMessage());
            System.exit(1);
            return;
        }

        System.out.println("Type a command: query | enrol | withdraw | transfer | update | quit");
            

        
        
        // ==================== Interactive command loop (provided) ====================
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
        try {
            String line;
            while ((line = stdin.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                String[] tokens = trimmed.split("\\s+");
                String command = tokens[0].toLowerCase();

                switch (command) {
                    case "query":
                        if (tokens.length != 2) { printUsage("query <subjectCode>"); break; }
                        handleQuery(tokens[1]);
                        break;

                    case "enrol":
                        if (tokens.length != 3) { printUsage("enrol <subjectCode> <studentId>"); break; }
                        handleEnrol(tokens[1], tokens[2]);
                        break;

                    case "withdraw":
                        if (tokens.length != 3) { printUsage("withdraw <subjectCode> <studentId>"); break; }
                        handleWithdraw(tokens[1], tokens[2]);
                        break;

                    case "transfer":
                        if (tokens.length != 4) { printUsage("transfer <fromSubjectCode> <toSubjectCode> <studentId>"); break; }
                        handleTransfer(tokens[1], tokens[2], tokens[3]);
                        break;

                    case "update":
                        if (tokens.length != 3) { printUsage("update <subjectCode> <newCapacity>"); break; }
                        handleUpdateCapacity(tokens[1], tokens[2]);
                        break;

                    case "quit":
                       
                        System.out.println("Goodbye.");
                        // ======== Close the socket and exit cleanly. ====================
                        try {
                            serverWriter.close(); // Close the writer first to flush any remaining data.
                            serverReader.close();
                            clientSocket.close();
                            return;
                        } catch (IOException e) {
                            System.err.println("Error closing connection: " + e.getMessage());
                            return;
                        }


                    default:
                        System.out.println("Unrecognised command: " + command);
                        printUsage("query | enrol | withdraw | transfer | update | quit");
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading from standard input: " + e.getMessage());
        }
    }

    private static void printUsage(String usage) {
        System.out.println("Usage: " + usage);
    }


    /* step 8, 10
    * Handles the 'query' command by sending a query request to the server and displaying the response.
    * @param subjectCode The code of the subject to query.
    * @throws IOException If an I/O error occurs while communicating with the server.
    * @throws ProtocolException If the server's response cannot be parsed as a valid protocol message.
    */
    private static void handleQuery(String subjectCode) {
        try { // TODO
            serverWriter.write(ProtocolMessage.queryRequest(subjectCode).toJson());
            serverWriter.write("\n");         
            serverWriter.flush();   
            String responseLine = serverReader.readLine();
            if (responseLine == null) {
                throw new IOException("server closed.");
            }
            
            ProtocolMessage response = ProtocolMessage.parse(responseLine);
            String status = response.getStatus();
            if (! ProtocolMessage.STATUS_SUCCESS.equals(status)) {
                System.out.println(status + ": " + response.getString("message"));
            } else {
                Map<String, Object> data = response.getData();
                System.out.println("Subject: " + subjectCode);
                System.out.println("Capacity: " + data.get("capacity"));
                System.out.println("Enrolled Count: " + data.get("enrolledCount"));
                System.out.println("Enrolled Student IDs: " + data.get("enrolledStudentIds"));
            }
        } catch (IOException e) {
            serverConnectionError(e);
        } catch (ProtocolException e) {
            System.err.println("Error parsing query response: " + e.getMessage());
        }
       

    }
    /* step 11
    * Handles the 'enrol' command by sending an enrol request to the server and displaying the response.
    * @param subjectCode The code of the subject to enrol in.
    * @param studentId The ID of the student to enrol.
    * @throws IOException If an I/O error occurs while communicating with the server.
    * @throws ProtocolException If the server's response cannot be parsed as a valid protocol message.
    */
    private static void handleEnrol(String subjectCode, String studentId) {
        
        try { 
            // write the enrol request to the server
            serverWriter.write(ProtocolMessage.enrolRequest(subjectCode, studentId).toJson());
            serverWriter.write("\n");         
            serverWriter.flush(); // flush to ensure the request is sent immediately   
            String responseLine = serverReader.readLine(); // read the response line from the server
            if (responseLine == null) {
                throw new IOException("server closed.");
            }
            // parse the response line into a ProtocolMessage object
            ProtocolMessage response = ProtocolMessage.parse(responseLine);
            String status = response.getStatus();
            if (! ProtocolMessage.STATUS_SUCCESS.equals(status)) {
                System.out.println(status + ": " + response.getString("message"));
            } else {
                System.out.println(status);
            }

        } catch (IOException e) {
            serverConnectionError(e);
        } catch (ProtocolException e) {
            System.err.println("Error parsing enrol response: " + e.getMessage());

        }
    }
    /* step 12
    * Handles the 'withdraw' command by sending a withdraw request to the server and displaying the response.
    * @param subjectCode The code of the subject to withdraw from.
    * @param studentId The ID of the student to withdraw.
    * @throws IOException If an I/O error occurs while communicating with the server.
    * @throws ProtocolException If the server's response cannot be parsed as a valid protocol message.
    */
    private static void handleWithdraw(String subjectCode, String studentId) {
        
        try { 
            serverWriter.write(ProtocolMessage.withdrawRequest(subjectCode, studentId).toJson());
            serverWriter.write("\n");         
            serverWriter.flush();   
            String responseLine = serverReader.readLine();
            if (responseLine == null) {
                throw new IOException("server closed.");
            }
    
            ProtocolMessage response = ProtocolMessage.parse(responseLine);
            String status = response.getStatus();
            if (! ProtocolMessage.STATUS_SUCCESS.equals(status)) {
                System.out.println(status + ": " + response.getString("message"));
            } else {
                System.out.println(status);
            }

        } catch (IOException e) {
            serverConnectionError(e);
        } catch (ProtocolException e) {
            System.err.println("Error parsing withdraw response: " + e.getMessage());

        }
    }
    /* step 13
    * Handles the 'transfer' command by sending a transfer request to the server and displaying the response.
    * @param fromSubjectCode The code of the subject to transfer from.
    * @param toSubjectCode The code of the subject to transfer to.
    * @param studentId The ID of the student to transfer.
    * @throws IOException If an I/O error occurs while communicating with the server.
    * @throws ProtocolException If the server's response cannot be parsed as a valid protocol message.
    */
    private static void handleTransfer(String fromSubjectCode, String toSubjectCode, String studentId) {
        
        try { 
            serverWriter.write(ProtocolMessage.transferRequest(fromSubjectCode, toSubjectCode, studentId).toJson());
            serverWriter.write("\n");         
            serverWriter.flush();   
            String responseLine = serverReader.readLine();
            if (responseLine == null) {
                throw new IOException("server closed.");
            }
            ProtocolMessage response = ProtocolMessage.parse(responseLine);
            String status = response.getStatus();
            if (! ProtocolMessage.STATUS_SUCCESS.equals(status)) {
                System.out.println(status + ": " + response.getString("message"));
            } else {
                System.out.println(status);
            }
        } catch (IOException e) {
            serverConnectionError(e);
        } catch (ProtocolException e) {
            System.err.println("Error parsing transfer response: " + e.getMessage());

        }
    }

    /* step 14
    * Handles the 'updateCapacity' command by sending an update capacity request to the server and displaying the response.
    * @param subjectCode The code of the subject to update capacity for.
    * @param newCapacityStr The new capacity as a string.
    * @throws IOException If an I/O error occurs while communicating with the server.
    * @throws ProtocolException If the server's response cannot be parsed as a valid protocol message.
    */
    private static void handleUpdateCapacity(String subjectCode, String newCapacityStr) {
        int newCapacity;
        try {
            newCapacity = Integer.parseInt(newCapacityStr);
        } catch (NumberFormatException e) {
            System.out.println("newCapacity must be an integer.");
            return;
        }

        try { 
            serverWriter.write(ProtocolMessage.updateCapacityRequest(subjectCode, newCapacity).toJson());
            serverWriter.write("\n");         
            serverWriter.flush();   
            String responseLine = serverReader.readLine();
            if (responseLine == null) {
                throw new IOException("server closed.");
            }
            ProtocolMessage response = ProtocolMessage.parse(responseLine);
            String status = response.getStatus();
            if (! ProtocolMessage.STATUS_SUCCESS.equals(status)) {
                System.out.println(status + ": " + response.getString("message"));
            } else {
                System.out.println(status);
            }
        } catch (IOException e) {
            serverConnectionError(e);
        } catch (ProtocolException e) {
            System.err.println("Error parsing update capacity response: " + e.getMessage());

        }
    }

    // FAQ connection error handling - report the error and exit the program. 
    // called from the handlexxx methods when an IOException occurs while reading/writing to the server.   
    private static void serverConnectionError(IOException e) {
    System.err.println("Connection to server lost: " + e.getMessage());
    System.exit(1);
}
}
