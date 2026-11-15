package com.cards.mail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sends through Resend's REST API (https://resend.com/docs/api-reference/emails/send-email): POST /emails with a Bearer key.
 * HTTPS on 443 works on hosts that block outbound SMTP (free Render). The key is only ever used in the Authorization header.
 */
public class ResendEmailSender implements EmailSender {

    static final String DEFAULT_ENDPOINT = "https://api.resend.com/emails";

    private final HttpClient http;
    private final ObjectMapper json;
    private final URI endpoint;
    private final String apiKey;
    private final String from;

    public ResendEmailSender(HttpClient http, ObjectMapper json, String endpoint, String apiKey, String from) {
        this.http = http;
        this.json = json;
        this.endpoint = URI.create(endpoint);
        this.apiKey = apiKey;
        this.from = from;
    }

    public static ResendEmailSender create(ObjectMapper json, String apiKey, String from) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return new ResendEmailSender(client, json, DEFAULT_ENDPOINT, apiKey, from);
    }

    @Override
    public void send(Email email) throws EmailException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", from);
        body.put("to", email.to());
        body.put("subject", email.subject());
        body.put("html", email.html());
        body.put("text", email.text());
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        } catch (JsonProcessingException e) {
            throw new EmailException("Could not build the email request", e);
        }
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new EmailException("The email provider answered HTTP " + response.statusCode());   // the body may echo input: not logged
            }
        } catch (IOException e) {
            throw new EmailException("The email provider could not be reached: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmailException("Interrupted while sending an email");
        }
    }
}
