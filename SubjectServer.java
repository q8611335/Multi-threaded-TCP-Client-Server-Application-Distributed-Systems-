import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
/**
 * SubjectServer - starter skeleton.
 *
 * WHAT'S ALREADY DONE FOR YOU:
 *   - Command-line argument parsing and validation.
 *   - Reading the subject data file and decoding it as JSON.
 *
 * WHAT YOU NEED TO IMPLEMENT (see the TODOs below):
 *   - Validating the decoded subject data against the rules in the
 *     Subject Data File Format section (unique subjectCode, positive
 *     capacity, enrolledStudentIds.length <= capacity), and deciding
 *     what data structure you actually want to hold subjects in for the
 *     rest of the server's life.
 *   - Everything socket-related: opening a ServerSocket, accepting
 *     connections, and handling each one.
 *   - Everything thread-related: your chosen concurrency model
 *     (thread-per-connection, thread-per-request, or a worker pool).
 *   - Your locking strategy for the concurrency requirements (per-subject
 *     read/write locks, the artificial delay placement, and the
 *     lock-ordering needed to make TRANSFER deadlock-free).
 *   - The server console (status / stop commands) and the operational
 *     log.
 *   - Persistence: writing state to disk after every successful write,
 *     and reloading it on restart instead of the original subject data
 *     file.
 *
 * None of that is scaffolded on purpose - it's the actual point of the
 * assignment. Use ProtocolMessage (see its own Javadoc) to build your
 * responses and parse incoming requests; you should not need to touch
 * SimpleJson directly.
 *
 * Usage:
 *   java -jar SubjectServer.jar <port> <subject-data-file> <artificial-delay-ms>
 */
public class SubjectServer {
    private static int artificialDelayMs;
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"); // step 27
    
    private static final Object connectionLock = new Object(); //step 25, 17
    private static int activeConnections = 0; 

    private static volatile boolean running = true; // step 26
    private static int activeOperations = 0;
    private static final Set<Socket> openSockets = new LinkedHashSet<>(); // step 26
    private static final long MIN_GRACE_MS = 10000; // step 26: set at least 10 seconds grace period for in-flight operations to finish before forcefully closing sockets
    public static void main(String[] args) {

        // ==================== Argument parsing (provided) ====================
        if (args.length != 3) {
            System.err.println("Usage: java -jar SubjectServer.jar <port> <subject-data-file> <artificial-delay-ms>");
            System.exit(1);
            return;
        }

        int port = -1;
        try {
            port = Integer.parseInt(args[0]);
            if (port < 0 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            System.err.println("Invalid port '" + args[0] + "': must be an integer between 0 and 65535.");
            System.exit(1);
            return;
        }

        String subjectDataFile = args[1];

        int delayMs = -1;
        try {
            delayMs = Integer.parseInt(args[2]);
            if (delayMs < 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            System.err.println("Invalid artificial delay '" + args[2] + "': must be a non-negative integer, in milliseconds.");
            System.exit(1);
            return;
        }

        artificialDelayMs = delayMs;
       
        // ==================== Load the subject data file (partly provided) ====================
        final Map<String, Subject> subjects = loadSubjects(subjectDataFile); // add final to ensure subjects is fixed (step 5)
        System.out.println("Loaded " + subjects.size() + " subject(s) from " + subjectDataFile);

        overlayPersisted(subjects); //step 23

        // ==================== Start the server ====================
        // open a ServerSocket on the specified port, and print a message to
        // standard output indicating that the server has started and is
        // listening for connections. If the ServerSocket cannot be opened,
        // print a clear error message to standard error and exit with a
        // non-zero status code.
        ServerSocket serverSocket = null;
        try {
            serverSocket = new ServerSocket(port);
            System.out.println("Server started on port " + port);
           
        } catch (IOException e) {
            System.err.println("Could not start server on port " + port + ": " + e.getMessage());
            System.exit(1);
            return; // unreachable
            
        }
        final int consolePort = port; // step 25
        final ServerSocket listener = serverSocket; // step 26
      
       new Thread (() -> runConsole(subjects, consolePort, listener)).start(); //step 25, 26


        while (running) { //step 26 
            try {
                Socket clientSocket = serverSocket.accept();
                new Thread(() -> handleClient(clientSocket, subjects)).start(); //step 16
            } catch (IOException e) { 
                if (!running){
                    break;
                }
                System.err.println("Error accepting connection: " + e.getMessage());

            }      
                
        } 

        shutdown(subjects);

    }
    
    /**
     * Reads the subject data file, decodes it as JSON, and returns a
     * subjectCode -> Subject map. Exits the process with a clear error
     * message (and non-zero status) on any failure, per the Subject Data
     * File Format section.
     * @param path The path to the subject data file.
     * @return A map of subject codes to Subject objects.
     */ 
    private static Map<String, Subject> loadSubjects(String path) {
        String text;
        try {
            text = new String(Files.readAllBytes(Paths.get(path)));
        } catch (IOException e) {
            System.err.println("Could not read subject data file '" + path + "': " + e.getMessage());
            System.exit(1);
            return null; // unreachable, System.exit terminates the JVM
        }

        Object decoded;
        try {
            decoded = SimpleJson.decode(text);
        } catch (SimpleJson.JsonParseException e) {
            System.err.println("Subject data file '" + path + "' is not valid JSON: " + e.getMessage());
            System.exit(1);
            return null; // unreachable
        }

        if (!(decoded instanceof List)) {
            System.err.println("Subject data file '" + path + "' must contain a JSON array at the top level.");
            System.exit(1);
            return null; // unreachable
        }

        Map<String, Subject> subjects = new HashMap<>();

        for (Object item : (List<?>) decoded) {
            if (!(item instanceof Map)) {
                System.err.println("Every entry in '" + path + "' must be a JSON object.");
                System.exit(1);
                return null; // unreachable
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) item;
           

            // step 3 method parseSubject() will validate the entry and return a Subject object if valid, or exit with an error if invalid.
            Subject subject = parseSubject(entry, path);

            if (subjects.containsKey(subject.getSubjectCode())) {
                System.err.println("Duplicate subject code: " + subject.getSubjectCode() + " in " + path);
                System.exit(1);
                return null;
            }
            subjects.put(subject.getSubjectCode(), subject);
        }

            if (subjects.isEmpty()) {
            System.err.println("Subject data file '" + path + "' contains no subjects.");
            System.exit(1);
        }   
            return subjects;
    }

    /* step 3
     * Parses a single subject entry from the subject data file, validating
     * it against the rules in the Subject Data File Format section. If any
     * rule is violated, prints a clear error message to standard error and
     * exits with a non-zero status code.
     * @param entry The Map representing a single subject entry from the JSON array.
     * @param sourceLabel A string indicating the source of the entry (e.g., the filename) for error messages.
     * @return A Subject object if the entry is valid, or exits the process if invalid.
     * 
     */
    private static Subject parseSubject(Map<String, Object> entry, String sourceLabel) {
            // Validate subject code
            Object codeObj = entry.get("subjectCode");
            if (!(codeObj instanceof String) || ((String) codeObj).isEmpty()) {
                System.err.println("Subject code must be a non-empty string" + " in " + sourceLabel); 
                System.exit(1);
                return null;
            }
            String subjectCode = (String) codeObj;

            // Validate capacity
            Integer capacity = null;
            Object capObj = entry.get("capacity");
            if (capObj instanceof Long) {
                long cap = (Long) capObj;
                if (cap > 0 && cap <= Integer.MAX_VALUE) {
                    capacity = (int) cap;
                }
            } else if (capObj instanceof Double) {
                double cap = (Double) capObj;
                if (cap == Math.floor(cap) && cap > 0 && cap <= Integer.MAX_VALUE) {
                    capacity = (int) cap;
                }
            }

            if (capacity == null || capacity <= 0) {
                System.err.println("Capacity must be a positive integer for subject: " + subjectCode + " in " + sourceLabel);
                System.exit(1);
                return null;
            }

            // Validate enrolled student IDs
            Set<String> enrolledIds = new LinkedHashSet<>();
            Object enrolledObj = entry.get("enrolledStudentIds");
            if (!(enrolledObj instanceof List)) {
                System.err.println("enrolledStudentIds must be an array for subject: " + subjectCode + " in " + sourceLabel);
                System.exit(1);
                return null;
            }

            for (Object id : (List<?>) enrolledObj) {
                if (!(id instanceof String)) {
                    System.err.println("Each enrolled student ID must be a string for subject: " + subjectCode + " in " + sourceLabel);
                    System.exit(1);
                    return null;
                }
                enrolledIds.add((String) id);
            }

            if (enrolledIds.size() > capacity) {
                System.err.println("Enrolled students exceed capacity for subject: " + subjectCode + " in " + sourceLabel);
                System.exit(1);
                return null;
            }

            return new Subject(subjectCode, capacity, enrolledIds);
    }

    /** step 23
     * Overlays any persisted subject state from the "state" directory onto
     * the subjects map. If a subject has a corresponding .json file in the
     * "state" directory, its state is loaded and replaces the initial state
     * from the subject data file. If the "state" directory does not exist or
     * is empty, this method does nothing. Exits with a clear error message
     * and non-zero status if any persisted file is invalid or cannot be read. 
     * @param subjects The map of subjectCode to Subject objects to overlay persisted state onto.
     */
    private static void overlayPersisted(Map<String, Subject> subjects) {
        File stateDir = new File("state");
        File[] files = stateDir.listFiles();

        if (files == null) {
            return; // 1st started time, no state dir yet
        }
        // check file type 
        for (File f : files) {
            if (!f.getName().endsWith(".json")) {
                continue; // skip if file is not .json (.tmp etc)
            }
        // read file
        String text;
        try {
            text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Could not read subject data file '" + f.getName() + "': " + e.getMessage());
            System.exit(1);
            return;
        }
        // decode json and check if the decoded object is a Map, if not exit with error
        Object decoded;
        try {
            decoded = SimpleJson.decode(text);
        } catch (SimpleJson.JsonParseException e) {
            System.err.println("Subject data file '" + f.getName() + "' is not valid JSON: " + e.getMessage());
            System.exit(1);
            return;

        }

        if (!(decoded instanceof Map)) {
            System.err.println("Subject data file '" + f.getName() + "' must contain a JSON object at the top level.");
            System.exit(1);
            return;

        }
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) decoded;

        // parse subject and overlay onto subjects map
        Subject s = parseSubject(entry, f.getName());
        subjects.put(s.getSubjectCode(), s);
    }
    }
    /* step 25
    * Runs the server console in a separate thread, allowing the user to
    * enter commands while the server is running. The console supports
    * two commands: "status" and "quit". The "status" command prints the
    * current server status, including the port, artificial delay, active
    * connections, and subject information. The "quit" command shuts down
    * the server gracefully.
    * @param subjects The map of subjectCode to Subject objects.    
    * @param port The port number the server is listening on.
    * @param listener The server socket listening for connections.
    */
    private static void runConsole(Map<String, Subject> subjects, int port, ServerSocket listener) {
        BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
        String line;
        try {
            while((line = console.readLine()) != null) {
                String command = line.trim().toLowerCase();

                switch (command) {
                    case "status": {
                        int count;
                        // synchronize access to activeConnections to ensure thread safety when reading the count of active connections.
                        synchronized (connectionLock) { count = activeConnections;} // step 17

                        System.out.println("=== Server status ===");
                        System.out.println("Port: " + port);
                        System.out.println("Artificial delay: " + artificialDelayMs + " ms");
                        System.out.println("Active connections: " + count);
                        System.out.println("Subjects: " + subjects.size()); 
                        for (Subject s : subjects.values()) {
                            // acquire read lock to safely read subject information
                            s.getLock().readLock().lock();
                            try {
                                System.out.println(s.getSubjectCode() + "  capacity=" + s.getCapacity() + "  enrolled=" + s.getEnrolledCount());                                    
                            } finally {
                                // release read lock after reading subject information
                                s.getLock().readLock().unlock();
                            }
                        }                
                        break;}
                    case "quit":
                        System.out.println("Shutting down.");
                        // set running to false to stop accepting new connections and exit the main loop
                        running = false; // step 26
                        try {
                            // close the server socket to stop accepting new connections
                            listener.close();
                        } catch (IOException e) {
                            System.err.println("Error closing server socket: " + e.getMessage());
                        }
                        return; 
                    case "":
                        break;
                    default:
                        System.out.println("Unknown command. Available: status, quit");
                        break;
                }
            }
        }catch (IOException e) {
            System.err.println("Console input error: " + e.getMessage());
        }
    }
    


    /* step 16
    * Handles a client connection in a separate thread. 
    * Reads requests from the client, processes them, and sends back responses.
    * Uses a try-with-resources statement to ensure that the client socket and its associated streams are closed automatically when the method exits.
    * Synchronizes access to the activeConnections and openSockets variables to ensure thread safety when updating these shared resources.
    * @param clientSocket The socket representing the client connection.
    * @param subjects The map of subjectCode to Subject objects.
    */
    private static void handleClient(Socket clientSocket, Map<String, Subject> subjects) {
        synchronized (connectionLock) {
            activeConnections++; // step 17
            openSockets.add(clientSocket); // step 26
        } 
    try (Socket socket = clientSocket;  // declared as a resource so it is closed automatically
        BufferedReader clientReader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream())); // from byte - char - line
        BufferedWriter clientWriter = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream()));
    ) // try-with-resources for automatic closing of resources when done, even if an exception occurs
    {
        
        String line;
        
        while ((line = clientReader.readLine())!= null) {
            synchronized (connectionLock) {activeOperations++;} //step 26
            try {
            ProtocolMessage response;
            ProtocolMessage request = null;
            try {
                request = ProtocolMessage.parse(line);
                String op = request.requireString("op"); 
                switch (op) {
                    case ProtocolMessage.OP_QUERY:
                        response = handleQuery(request, subjects);
                        break;
                    case ProtocolMessage.OP_ENROL:
                        response = handleEnrol(request, subjects);
                        break;
                    case ProtocolMessage.OP_WITHDRAW:
                        response = handleWithdraw(request, subjects);
                        break;
                    case ProtocolMessage.OP_TRANSFER:
                        response = handleTransfer(request, subjects);
                        break;
                    case ProtocolMessage.OP_UPDATE_CAPACITY:
                        response = handleUpdateCapacity(request, subjects);
                        break;
                    default:
                        // protocol-level faults: unknown operation
                        response = ProtocolMessage.errorResponse(ProtocolMessage.STATUS_ERROR, "Unknown operation: " + op);
                        break;
                }
            } catch (ProtocolException e) {
                // protocol-level faults: malformed request
                response = ProtocolMessage.errorResponse(ProtocolMessage.STATUS_ERROR, "Malformed request: " + e.getMessage());
            }
            
            clientWriter.write(response.toJson());
            clientWriter.write("\n");
            clientWriter.flush();
            logOperation(request, response); // step 27
        } finally {
            // synchronize access to activeOperations to ensure thread safety when decrementing the count of active operations. 
            // to maintain an accurate count of ongoing operations, esp in multi-thread environment as multiple client requests may be processed concurrently.
            synchronized (connectionLock) {activeOperations--;} //step 26
        }
        }
    }
    catch (IOException e) {
        // only report if the server is still running: during shutdown the IOException comes from
        // closing this socket deliberately, not from a real failure
        if (running) { 
        System.err.println("Connection error: " + e.getMessage());
        }
    } finally {
        // synchronize access to activeConnections and openSockets to ensure thread safety when updating these shared resources.
        // to maintain an accurate count of active connections and to ensure that the set of open sockets is consistent, esp in multi-thread environment as multiple client connections may be handled concurrently.
        synchronized (connectionLock) {
            activeConnections--; //step 17
            openSockets.remove(clientSocket); // step 26
        } 
    }  
}

    /* step 10
    * Handles a QUERY request from a client. 
    * Acquires a read lock on the subject to ensure that the subject's state is not modified while the query is being processed.
    * Applies an artificial delay to simulate processing time.
    * Releases the read lock and returns the response.
    * @param request The ProtocolMessage representing the QUERY request.
    * @param subjects The map of subjectCode to Subject objects.
    * @return A ProtocolMessage representing the response to the QUERY request.
    * @throws ProtocolException If the request is malformed or if the subject code is invalid.
    */
    private static ProtocolMessage handleQuery(ProtocolMessage request, Map<String, Subject> subjects) throws ProtocolException {
        String subjectCode = request.requireString("subjectCode");
        Subject subject = subjects.get(subjectCode);
        if (subject == null) {
            return ProtocolMessage.errorResponse(ProtocolMessage.STATUS_NOT_FOUND, "invalid subject code." );
        }
        // acquire read lock 
        subject.getLock().readLock().lock();

        try {
            applyDelay(); // step 19
            return ProtocolMessage.successResponse(
            ProtocolMessage.queryData(
                subjectCode,
                subject.getCapacity(),
                subject.getEnrolledCount(),
                new ArrayList<>(subject.getEnrolledStudentIds())));
        } finally {
            subject.getLock().readLock().unlock();
                    }
    }
    /* step 11
    * Handles an ENROL request from a client. 
    * Acquires a write lock on the subject to ensure that the subject's state is not modified by other threads.
    * Applies an artificial delay to simulate processing time
    * Releases the write lock and returns the response.
    * @param request The ProtocolMessage representing the ENROL request.
    * @param subjects The map of subjectCode to Subject objects.        
    * @return A ProtocolMessage representing the response to the ENROL request.
    * @throws ProtocolException If the request is malformed or if the subject code is invalid.
    */  
    private static ProtocolMessage handleEnrol(ProtocolMessage request, Map<String, Subject> subjects) throws ProtocolException {
        String subjectCode = request.requireString("subjectCode");
        String studentId = request.requireString("studentId");
        Subject subject = subjects.get(subjectCode);
        if (subject == null) {
            return ProtocolMessage.errorResponse(ProtocolMessage.STATUS_NOT_FOUND, "invalid subject code." );
        }
        // acquire write lock
        subject.getLock().writeLock().lock();
        try {
            applyDelay(); // step 19
            if (subject.getEnrolledStudentIds().contains(studentId)) {
            return ProtocolMessage.errorResponse(ProtocolMessage.STATUS_DUPLICATE_ENROLMENT, "student is already enrolled in this subject.");
        }
        if (subject.getEnrolledCount() >= subject.getCapacity()) {
            return ProtocolMessage.errorResponse(ProtocolMessage.STATUS_FULL, "subject is full.");
        }

        subject.getEnrolledStudentIds().add(studentId);
        persistSubject(subject); //step 22
        return ProtocolMessage.successResponse();
        } finally {
            subject.getLock().writeLock().unlock();
        }
        
    }

    /* step 12
    * Handles a WITHDRAW request from a client.
    * Acquires a write lock on the subject to ensure that the subject's state is not modified by other threads.
    * Applies an artificial delay to simulate processing time.
    * Releases the write lock and returns the response.
    * @param request The ProtocolMessage representing the WITHDRAW request.
    * @param subjects The map of subjectCode to Subject objects.
    * @return A ProtocolMessage representing the response to the WITHDRAW request.
    * @throws ProtocolException If the request is malformed or if the subject code is invalid.
    */
    private static ProtocolMessage handleWithdraw(ProtocolMessage request, Map<String, Subject> subjects) throws ProtocolException {
        String subjectCode = request.requireString("subjectCode");
        String studentId = request.requireString("studentId");
        Subject subject = subjects.get(subjectCode);
        if (subject == null) {
            return ProtocolMessage.errorResponse(ProtocolMessage.STATUS_NOT_FOUND, "invalid subject code." ); 
        }
        // acquire write lock
        subject.getLock().writeLock().lock();
        try {
            applyDelay(); // step 19
        if (!subject.getEnrolledStudentIds().contains(studentId)) {
            return ProtocolMessage.errorResponse(ProtocolMessage.STATUS_NOT_ENROLLED, "student is not enrolled in this subject.");
        }

        subject.getEnrolledStudentIds().remove(studentId);
        persistSubject(subject); //step 22
        return ProtocolMessage.successResponse();
        } finally {
            subject.getLock().writeLock().unlock();
        }
    }

    /* step 13
    * Handles a TRANSFER request from a client.
    * Acquires write locks on both the source and destination subjects to ensure that their states are not modified by other threads.
    * Applies an artificial delay to simulate processing time.          
    * Releases the write locks and returns the response.
    * @param request The ProtocolMessage representing the TRANSFER request.
    * @param subjects The map of subjectCode to Subject objects.
    * @return A ProtocolMessage representing the response to the TRANSFER request.
    * @throws ProtocolException If the request is malformed or if either subject code is invalid.
    */
    private static ProtocolMessage handleTransfer(ProtocolMessage request, Map<String, Subject> subjects) throws ProtocolException {
        String fromSubjectCode = request.requireString("fromSubjectCode");
        String toSubjectCode = request.requireString("toSubjectCode");
        String studentId = request.requireString("studentId");

        Subject fromSubject = subjects.get(fromSubjectCode);
        Subject toSubject = subjects.get(toSubjectCode);

        if (fromSubject == null || toSubject == null) {
            return ProtocolMessage.errorResponse(ProtocolMessage.STATUS_NOT_FOUND, "invalid subject code.");
        }
        if (fromSubjectCode.equals(toSubjectCode)) {
            return ProtocolMessage.errorResponse(ProtocolMessage.STATUS_INVALID_REQUEST, "transfer subjects must be different.");
        }
        
       Subject first, second; // Determine lock order to avoid deadlock
       if (fromSubjectCode.compareTo(toSubjectCode) < 0) {
            first = fromSubject;
            second = toSubject;
        } else {
            first = toSubject;
            second = fromSubject;
        }
        // acquire write locks in a consistent order 
        first.getLock().writeLock().lock();
        try{
            second.getLock().writeLock().lock();
            try {
                applyDelay(); // step 19
                if (!fromSubject.getEnrolledStudentIds().contains(studentId)) {
                return ProtocolMessage.errorResponse(ProtocolMessage.STATUS_NOT_ENROLLED, "student is not enrolled in the source subject.");
        }
                if (toSubject.getEnrolledStudentIds().contains(studentId)) {
                return ProtocolMessage.errorResponse(ProtocolMessage.STATUS_DUPLICATE_ENROLMENT, "student is already enrolled in the destination subject.");
        }

                if (toSubject.getEnrolledCount() >= toSubject.getCapacity()) {
                return ProtocolMessage.errorResponse(ProtocolMessage.STATUS_FULL, "destination subject is full.");
        }

                fromSubject.getEnrolledStudentIds().remove(studentId);
                toSubject.getEnrolledStudentIds().add(studentId);
                persistSubject(fromSubject); //step 22
                persistSubject(toSubject); //step 22

                return ProtocolMessage.successResponse();
            } finally{
                // release lock in reverse order to avoid deadlock
                second.getLock().writeLock().unlock();
            }
        } finally {
            first.getLock().writeLock().unlock();
        }

        
    }

    /* step 14
    * Handles an UPDATE_CAPACITY request from a client.
    * Acquires a write lock on the subject to ensure that the subject's state is not modified by other threads.
    * Applies an artificial delay to simulate processing time.
    * Releases the write lock and returns the response.
    * @param request The ProtocolMessage representing the UPDATE_CAPACITY request.
    * @param subjects The map of subjectCode to Subject objects.
    * @return A ProtocolMessage representing the response to the UPDATE_CAPACITY request.
    * @throws ProtocolException If the request is malformed or if the subject code is invalid.
    */
    private static ProtocolMessage handleUpdateCapacity(ProtocolMessage request, Map<String, Subject> subjects) throws ProtocolException {
        String subjectCode = request.requireString("subjectCode");
        int newCapacity = request.requireInt("newCapacity");
        Subject subject = subjects.get(subjectCode);
        if (subject == null) {
            return ProtocolMessage.errorResponse(ProtocolMessage.STATUS_NOT_FOUND, "invalid subject code.");
        }
        // acquire write lock
        subject.getLock().writeLock().lock();
        try {
            applyDelay(); // step 19
            if (newCapacity < subject.getEnrolledCount() || newCapacity <= 0) {
            return ProtocolMessage.errorResponse(ProtocolMessage.STATUS_INVALID_REQUEST, "the capacity is lower than current enrolled student or not a positive number.");
        }
            subject.setCapacity(newCapacity);
            persistSubject(subject); //step 22
            return ProtocolMessage.successResponse();
        } finally {
            subject.getLock().writeLock().unlock();
        }
    }


    /* step 19
    * Applies an artificial delay to simulate processing time. 
    * @throws InterruptedException If the thread is interrupted while sleeping.
    */  
    private static void applyDelay() {
            try {
                Thread.sleep(artificialDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // step26 Restore interrupted status
            }
        }


    /* step 22
     * Persists the current state of a subject to disk in the "state"
     * directory, using the subjectCode as the filename. The file is
     * written atomically (to avoid partial writes) and overwrites any
     * existing file for that subject.
     * @param subject The Subject object whose state is to be persisted.
     * @throws IOException If an I/O error occurs while writing the file.
     */
    private static void persistSubject(Subject subject) {
        // create a map of the subject's data to be serialized to JSON
        Map<String, Object> subjectMap = new LinkedHashMap<>();
        // populate the map with the subject's fields
        subjectMap.put("subjectCode", subject.getSubjectCode());
        subjectMap.put("capacity", subject.getCapacity());
        subjectMap.put("enrolledStudentIds", new ArrayList<>(subject.getEnrolledStudentIds()));
        String json = SimpleJson.encode(subjectMap);  // convert the subject data to JSON format
        // create the target file path in the "state" directory with the subjectCode as the filename
        Path target = Paths.get("state", subject.getSubjectCode() + ".json");
        try {    
            // create a temporary file in the "state" directory with a ".tmp" extension
            Path tmp = Paths.get("state", subject.getSubjectCode() + ".json.tmp");
            Files.createDirectories(target.getParent()); // ensure the "state" directory exists
            Files.write(tmp, json.getBytes(StandardCharsets.UTF_8)); // write the JSON data to the temporary file
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE); // atomically move the temporary file to the target file, overwriting any existing file
            
        } catch (IOException e) {
            System.err.println("PERSIST FAILED for'" + subject.getSubjectCode() + ".json': " + e.getMessage());
        }
    }
   


    /* step 27
     * Logs the details of a request and its corresponding response to the
     * console. The log includes the timestamp, thread name, operation,
     * subject code(s), and response status.
     * @param request The ProtocolMessage representing the client's request.
     * @param response The ProtocolMessage representing the server's response.
     */     
    private static void logOperation(ProtocolMessage request, ProtocolMessage response){
    String time = LocalDateTime.now().format(LOG_TIME);
    String thread = Thread.currentThread().getName();

    String op = "-";
    String subject = "-";
    if (request != null) {
    String opValue = request.getString("op");
    if (opValue != null) {
        op = opValue;
    }
    String code = request.getString("subjectCode");
    String from = request.getString("fromSubjectCode");
    String to   = request.getString("toSubjectCode");

    if (code != null) {
        subject = code;
    } else if (from != null && to != null) {
        subject = from + "->" + to;
    }    
    }

    String status = response.getStatus();
    System.out.println(String.format("%s  [%s]  %-16s %-24s %s", time, thread, op, subject, status));
    }
    /* step 26
     * Shuts down the server gracefully. 
     * Waits for in-flight operations to complete, closes all open sockets.
     * Persists the final state of each subject to disk. 
     * Exits the process with a status code of 0.
     * @param subjects The map of subjectCode to Subject objects.
     */ 
    private static void shutdown(Map<String, Subject> subjects) {
    // wait inflight operations complete up to 10s Or artificial delay * 2 + 1s, whichever is longer
    long graceMs = Math.max(MIN_GRACE_MS, artificialDelayMs * 2 + 1000); 
    long deadline = System.currentTimeMillis() + graceMs;
    while (System.currentTimeMillis() < deadline) {
    // check in-flight operations with lock to avoid race conditions. 
    // synchronized block ensures that the read of activeOperations is atomic and consistent with any updates made by other threads.
    int inFlight;
    synchronized (connectionLock) { inFlight = activeOperations; }
    if (inFlight == 0) {
        break;
    }
    try {
        // sleep for a 50ms to avoid busy waiting, allowing other threads to complete their operations and update the activeOperations count.
        Thread.sleep(50);
    } catch (InterruptedException e) {
        // the shutdown thread was interrupted while waiting
        // restore the flag and stop waiting. 
        Thread.currentThread().interrupt();
        break;
    }
    }

    // close client socket with lock to avoid race conditions. 
    // synchronized block ensures that the iteration over openSockets is thread-safe, preventing concurrent modifications while closing sockets.
    synchronized (connectionLock) {
        for (Socket s : openSockets) {
            try {
                s.close();
            } catch (IOException e) {
                // already closed or broken, which is the desired end state.
                // swallowed so one failure does not stop the remaining sockets closing.
            }
        }
    }
    // persist final state of each subject with lock to avoid race conditions.
    for (Subject s : subjects.values()){
        s.getLock().readLock().lock();
        try {
            persistSubject(s);
        } finally {
            s.getLock().readLock().unlock();
        }
    }
    // comletely shutdown
    System.out.println("Shutdown complete.");
    System.exit(0);
    }
}