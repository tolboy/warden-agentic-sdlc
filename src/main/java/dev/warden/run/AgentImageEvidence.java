package dev.warden.run;

import java.nio.file.*;
import javax.imageio.ImageIO;

/** Reject missing, truncated or non-image evidence before trusting a visual verdict. */
public final class AgentImageEvidence {
    private AgentImageEvidence() {}
    public static boolean isImage(Path file) {
        try {
            if (Files.size(file) == 0 || Files.size(file) > 32L * 1024 * 1024) return false;
            try (var stream = ImageIO.createImageInputStream(file.toFile())) {
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) return false;
                var reader = readers.next();
                try {
                    reader.setInput(stream);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    return width > 0 && height > 0 && (long) width * height <= 64_000_000
                            && reader.read(0) != null;
                } finally { reader.dispose(); }
            }
        } catch (Exception invalid) { return false; }
    }
}
