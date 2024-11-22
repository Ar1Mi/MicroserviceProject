package stud.uws.ii;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class RegistrationService {

    private static String dbUrl;
    private static String dbUsername;
    private static String dbPassword;
    private static int servicePort;

    static {
        loadConfig();
    }

    public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(servicePort)) {
            System.out.println("Registration Service uruchomiono w porcie " + servicePort);

            while (true) {
                try {
                    Socket clientSocket = serverSocket.accept();

                    new Thread(new ClientHandler(clientSocket)).start();
                } catch (IOException e) {
                    System.err.println("Błąd podczas przetwarzania żądania: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Nie udało się uruchomić Registration Service: " + e.getMessage());
        }
    }

    private static void loadConfig() {
        Properties properties = new Properties();
        try (InputStream input = new FileInputStream("config.properties")) {
            properties.load(input);
            dbUrl = properties.getProperty("database.url");
            dbUsername = properties.getProperty("database.username");
            dbPassword = properties.getProperty("db.password");
            servicePort = Integer.parseInt(properties.getProperty("registration.service.port"));
        } catch (IOException e) {
            System.err.println("Ошибка загрузки конфигурации: " + e.getMessage());
        }
    }

    private static class ClientHandler implements Runnable {
        private Socket clientSocket;

        public ClientHandler(Socket socket) {
            this.clientSocket = socket;
        }

        @Override
        public void run() {
            handleRequest(clientSocket);
        }

        private void handleRequest(Socket clientSocket) {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                 PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {

                String jsonRequest = in.readLine();
                ObjectMapper objectMapper = new ObjectMapper();
                Map<String, Object> request = objectMapper.readValue(jsonRequest, HashMap.class);

                // weryfikacja i rejestracja użytkownika
                Map<String, Object> response;
                Map<String, String> content = (Map<String, String>) request.get("content");
                String username = content.get("username");
                String password = content.get("password");

                if (registerUser(username, password)) {
                    response = createResponse(request, "success", "User registered successfully.");
                } else {
                    response = createResponse(request, "error", "User already exists.");
                }

                //Odesłanie odpowiedzi z powrotem do  API Gateway
                String jsonResponse = objectMapper.writeValueAsString(response);
                out.println(jsonResponse);

            } catch (IOException e) {
                System.err.println("Błąd podczas przetwarzania żądania: " + e.getMessage());
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    System.err.println("Błąd podczas zamykania połączenia: " + e.getMessage());
                }
            }
        }

        private Map<String, Object> createResponse(Map<String, Object> request, String status, String message) {
            Map<String, Object> response = new HashMap<>();
            response.put("type", "response");
            response.put("id", request.get("id"));
            response.put("status", status);
            response.put("message", message);
            return response;
        }

        private boolean registerUser(String username, String password) {
            try (Connection connection = DriverManager.getConnection(dbUrl, dbUsername, dbPassword);
                 PreparedStatement checkStmt = connection.prepareStatement("SELECT COUNT(*) FROM users WHERE username = ?");
                 PreparedStatement insertStmt = connection.prepareStatement("INSERT INTO users (username, password) VALUES (?, ?)")) {

                // Sprawdzenie czy użytkownik istnieje
                checkStmt.setString(1, username);
                ResultSet rs = checkStmt.executeQuery();
                rs.next();
                if (rs.getInt(1) > 0) {
                    return false; // Uzytkownik juz istnieje
                }

                // Dodawanie uzytkownika
                insertStmt.setString(1, username);
                insertStmt.setString(2, password);
                insertStmt.executeUpdate();
                return true;

            } catch (SQLException e) {
                System.err.println("Błąd podczas pracy z bazą danych: " + e.getMessage());
                return false;
            }
        }
    }
}
