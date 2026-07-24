package ru.itmo.nemat.vkconnector.persistence;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.itmo.nemat.shared.security.SecretCipher;

@Component
@Converter
@RequiredArgsConstructor
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final SecretCipher secretCipher;

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return secretCipher.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return secretCipher.decrypt(dbData);
    }
}
