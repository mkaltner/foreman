import type { ImagePayload } from "./protocol";

export const MAX_IMAGES = 4;
export const MAX_IMAGE_EDGE = 2048;
export const MAX_IMAGE_PAYLOAD_BYTES = 8 * 1024 * 1024;
export const IMAGE_TYPES = ["image/jpeg", "image/png", "image/webp"] as const;

export interface ProcessedImage extends ImagePayload {
  name: string;
  previewUrl: string;
  encodedBytes: number;
}

export function validateImageBatch(
  existing: ProcessedImage[],
  incoming: ProcessedImage[],
): void {
  if (existing.length + incoming.length > MAX_IMAGES) {
    throw new Error(`Attach at most ${MAX_IMAGES} images`);
  }
  const total = [...existing, ...incoming].reduce((sum, image) => sum + image.encodedBytes, 0);
  if (total > MAX_IMAGE_PAYLOAD_BYTES) {
    throw new Error("Combined encoded images must be 8 MiB or less");
  }
}

export async function processImage(file: File): Promise<ProcessedImage> {
  if (!IMAGE_TYPES.includes(file.type as (typeof IMAGE_TYPES)[number])) {
    throw new Error(`${file.name}: choose a JPEG, PNG, or WebP image`);
  }
  const bitmap = await createImageBitmap(file);
  const scale = Math.min(1, MAX_IMAGE_EDGE / Math.max(bitmap.width, bitmap.height));
  const width = Math.max(1, Math.round(bitmap.width * scale));
  const height = Math.max(1, Math.round(bitmap.height * scale));
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext("2d");
  if (!context) throw new Error("This browser cannot process images");
  context.drawImage(bitmap, 0, 0, width, height);
  bitmap.close();
  const requestedMimeType = file.type as ImagePayload["mimeType"];
  const blob = await new Promise<Blob>((resolve, reject) => {
    canvas.toBlob(
      (value) => (value ? resolve(value) : reject(new Error("Image conversion failed"))),
      requestedMimeType,
      requestedMimeType === "image/png" ? undefined : 0.84,
    );
  });
  const mimeType = IMAGE_TYPES.includes(blob.type as (typeof IMAGE_TYPES)[number])
    ? (blob.type as ImagePayload["mimeType"])
    : "image/png";
  const data = await blobBase64(blob);
  const previewUrl = `data:${mimeType};base64,${data}`;
  return {
    name: file.name,
    mimeType,
    data,
    previewUrl,
    encodedBytes: new TextEncoder().encode(data).byteLength,
  };
}

export async function processImages(
  files: File[],
  existing: ProcessedImage[] = [],
): Promise<ProcessedImage[]> {
  if (existing.length + files.length > MAX_IMAGES) {
    throw new Error(`Attach at most ${MAX_IMAGES} images`);
  }
  const processed = await Promise.all(files.map(processImage));
  validateImageBatch(existing, processed);
  return processed;
}

function blobBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error("Image could not be read"));
    reader.onload = () => {
      const value = String(reader.result ?? "");
      resolve(value.slice(value.indexOf(",") + 1));
    };
    reader.readAsDataURL(blob);
  });
}
