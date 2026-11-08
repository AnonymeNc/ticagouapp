package nc.ticagou.console.parc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.databind.JsonNode;

/** Lecture tolérante des états reçus : un champ absent ou illisible vaut nul. */
final class Json {

    private Json() {
    }

    static JsonNode at(JsonNode node, String pointer) {
        return node == null ? null : node.at(pointer);
    }

    static Integer integer(JsonNode node, String pointer) {
        var value = at(node, pointer);
        return value != null && value.isNumber() ? value.asInt() : null;
    }

    static Long number(JsonNode node, String pointer) {
        var value = at(node, pointer);
        return value != null && value.isNumber() ? value.asLong() : null;
    }

    static String text(JsonNode node, String pointer) {
        var value = at(node, pointer);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    static Instant instant(JsonNode node, String pointer) {
        var value = at(node, pointer);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (value.isNumber()) {
            return Instant.ofEpochMilli(value.asLong());
        }
        var text = value.asText();
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(text).toInstant();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    static LocalDate date(JsonNode node, String pointer) {
        var text = text(node, pointer);
        if (text == null) {
            return null;
        }
        try {
            return LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
