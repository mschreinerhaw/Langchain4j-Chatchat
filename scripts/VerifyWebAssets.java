import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;

/** Fail packaging before an HTML entrypoint with missing local resources can ship. */
public class VerifyWebAssets {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected static output directory");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path index = root.resolve("index.html");
        if (!Files.isRegularFile(index)) throw new IllegalStateException("Missing frontend index: " + index);
        var tags = Pattern.compile("<(script|link)\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(Files.readString(index));
        var attributes = Pattern.compile("\\b(src|href)\\s*=\\s*([\"'])(.*?)\\2", Pattern.CASE_INSENSITIVE);
        var missing = new ArrayList<String>();
        int scripts = 0;
        while (tags.find()) {
            var values = attributes.matcher(tags.group());
            while (values.find()) {
                if (!(tags.group(1).equalsIgnoreCase("script") && values.group(1).equalsIgnoreCase("src")
                        || tags.group(1).equalsIgnoreCase("link") && values.group(1).equalsIgnoreCase("href"))) continue;
                String reference = values.group(3);
                if (reference.isBlank() || reference.startsWith("#") || reference.startsWith("//")
                        || reference.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) continue;
                String relative = reference.split("[?#]", 2)[0];
                while (relative.startsWith("/")) relative = relative.substring(1);
                Path resource = root.resolve(relative).normalize();
                if (!resource.startsWith(root) || !Files.isRegularFile(resource) || Files.size(resource) == 0)
                    missing.add(reference);
                if (tags.group(1).equalsIgnoreCase("script")) scripts++;
            }
        }
        if (scripts == 0) throw new IllegalStateException("Frontend index has no local script entrypoint");
        if (!missing.isEmpty()) throw new IllegalStateException("Frontend resources missing or empty: " + missing
                + ". Run the frontend build before packaging; frontend.skip does not generate assets.");
        System.out.println("Frontend entrypoint resources verified: " + root);
    }
}
