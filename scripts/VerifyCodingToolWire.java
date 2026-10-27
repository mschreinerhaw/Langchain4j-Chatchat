import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import java.nio.file.Path;

/** Consumes schemas emitted by the real Node SDK, using the packaged Java libraries. */
class VerifyCodingToolWire {
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper();
        var tools = json.readTree(Path.of(args[0]).toFile());
        int count = 0;
        for (var tool : tools) {
            var wire = json.createObjectNode();
            wire.set("name", tool.get("name"));
            wire.set("description", tool.get("description"));
            wire.set("parameters", tool.get("inputSchema"));
            var parsed = ToolSpecification.fromJson(wire.toString());
            if (!(parsed.parameters() instanceof JsonObjectSchema))
                throw new AssertionError("Tool root must remain an object: " + parsed.name());
            count++;
        }
        if (count == 0) throw new AssertionError("No SDK tool schemas were checked");
        System.out.println("PASS: actual SDK tool schemas parsed by packaged Java libraries: " + count);
    }
}
