import { describe, expect, it } from "vitest";
import {
  MAX_IMAGE_PAYLOAD_BYTES,
  processImage,
  validateImageBatch,
  type ProcessedImage,
} from "./images";

const image = (encodedBytes: number): ProcessedImage => ({
  name: "image.jpg",
  mimeType: "image/jpeg",
  data: "x",
  previewUrl: "data:image/jpeg;base64,x",
  encodedBytes,
});

describe("browser image processing", () => {
  it("enforces the four-image and eight-MiB encoded aggregate limits", () => {
    expect(() => validateImageBatch([image(1), image(1)], [image(1), image(1)])).not.toThrow();
    expect(() => validateImageBatch([image(1), image(1)], [image(1), image(1), image(1)])).toThrow("at most 4");
    expect(() => validateImageBatch([], [image(MAX_IMAGE_PAYLOAD_BYTES + 1)])).toThrow("8 MiB");
  });

  it("rejects unsupported attachments before reading them", async () => {
    await expect(processImage(new File(["gif"], "bad.gif", { type: "image/gif" }))).rejects.toThrow(
      "JPEG, PNG, or WebP",
    );
  });
});
