package ru.itmo.nemat.vkconnector.controllers;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.itmo.nemat.vkconnector.dto.VkCallbackRequest;
import ru.itmo.nemat.vkconnector.services.VkWebhookService;

@RestController
@RequestMapping("/vk")
public class VkWebhookController {

    private final VkWebhookService webhookService;

    public VkWebhookController(VkWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/webhook")
    public ResponseEntity<String> handleVkWebhook(@RequestBody VkCallbackRequest request) {
        try {
            String response = webhookService.handleWebhook(request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Invalid secret");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error");
        }
    }
}