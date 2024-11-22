package stud.uws.ii;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class LoginService {

    private static String dbUrl;
    private static String dbUsername;
    private static String dbPassword;
    private static int servicePort;

    static {
        loadConfig();
    }

    public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(servicePort)) {
            System.out.println("Login Service uruchomiono w porcie " + servicePort);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(() -> handleRequest(clientSocket)).start();
            }
        } catch (IOException e) {
            System.err.println("Nie udało się uruchomić Login Service: " + e.getMessage());
        }
    }

    private static void handleRequest(Socket clientSocket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {

            String jsonRequest = in.readLine();
            ObjectMapper objectMapper = new ObjectMapper();
            Map<String, Object> request = objectMapper.readValue(jsonRequest, HashMap.class);

            // Sprawdzenie, czy użytkownik istnieje
            Map<String, Object> response;
            Map<String, String> content = (Map<String, String>) request.get("content");
            String username = content.get("username");
            String password = content.get("password");

            if (validateUser(username, password)) {
                response = createResponse(request, "success", "User login successful.");
            } else {
                response = createResponse(request, "error", "Invalid username or password.");
            }

            String jsonResponse = objectMapper.writeValueAsString(response);
            out.println(jsonResponse);

        } catch (IOException e) {
            System.err.println("Błąd podczas przetwarzania żądania: " + e.getMessage());
        }
    }

    private static Map<String, Object> createResponse(Map<String, Object> request, String status, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("type", "response");
        response.put("id", request.get("id"));
        response.put("status", status);
        response.put("message", message);
        return response;
    }

    private static boolean validateUser(String username, String password) {
        try (Connection connection = DriverManager.getConnection(dbUrl, dbUsername, dbPassword);
             PreparedStatement stmt = connection.prepareStatement("SELECT COUNT(*) FROM users WHERE username = ? AND password = ?")) {

            stmt.setString(1, username);
            stmt.setString(2, password);

            ResultSet rs = stmt.executeQuery();
            rs.next();
            return rs.getInt(1) > 0;

        } catch (SQLException e) {
            System.err.println("Błąd podczas pracy z bazą danych: " + e.getMessage());
            return false;
        }
    }

    private static void loadConfig() {
        Properties properties = new Properties();
        try (InputStream input = new FileInputStream("config.properties")) {
            properties.load(input);
            dbUrl = properties.getProperty("database.url");
            dbUsername = properties.getProperty("database.username");
            dbPassword = properties.getProperty("database.password");
            servicePort = Integer.parseInt(properties.getProperty("login.service.port"));
        } catch (IOException e) {
            System.err.println("Błąd ładowania konfiguracji: " + e.getMessage());
        }
    }
}
