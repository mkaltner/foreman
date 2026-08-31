import { Fragment, useEffect, useRef, useState } from "react";

export const COPY_FEEDBACK_DURATION_MS = 1_500;

export type CopyFeedbackState = "idle" | "copying" | "copied" | "failed";

const copyFeedbackLabel: Record<CopyFeedbackState, string> = {
  idle: "Copy",
  copying: "Copying",
  copied: "Copied",
  failed: "Copy failed",
};

export async function copyText(text: string): Promise<void> {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return;
    } catch {
      // Local Foreman hosts may not have a secure context; fall back to a selected textarea.
    }
  }

  const textarea = document.createElement("textarea");
  textarea.value = text;
  textarea.readOnly = true;
  textarea.style.position = "fixed";
  textarea.style.opacity = "0";
  document.body.appendChild(textarea);
  textarea.select();
  try {
    if (!document.execCommand?.("copy")) throw new Error("Clipboard unavailable");
  } finally {
    textarea.remove();
  }
}

export function CopyFeedbackButton({
  text,
  disabled = false,
  variant = "label",
  className = "",
}: {
  text: string;
  disabled?: boolean;
  variant?: "icon" | "label";
  className?: string;
}) {
  const [state, setState] = useState<CopyFeedbackState>("idle");
  const copyInFlight = useRef(false);
  const mounted = useRef(false);
  const resetTimer = useRef<number | null>(null);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      copyInFlight.current = false;
      if (resetTimer.current !== null) window.clearTimeout(resetTimer.current);
      resetTimer.current = null;
    };
  }, []);

  const copy = async () => {
    if (copyInFlight.current || disabled) return;
    copyInFlight.current = true;
    if (resetTimer.current !== null) window.clearTimeout(resetTimer.current);
    resetTimer.current = null;
    setState("copying");

    let result: CopyFeedbackState;
    try {
      await copyText(text);
      result = "copied";
    } catch {
      result = "failed";
    }

    copyInFlight.current = false;
    if (!mounted.current) return;
    setState(result);
    resetTimer.current = window.setTimeout(() => {
      resetTimer.current = null;
      setState("idle");
    }, COPY_FEEDBACK_DURATION_MS);
  };

  const label = copyFeedbackLabel[state];
  return (
    <Fragment>
      <button
        type="button"
        className={`copy-feedback ${state} ${className}`.trim()}
        aria-busy={state === "copying"}
        aria-label={label}
        title={label}
        disabled={disabled}
        onClick={() => void copy()}
      >
        <span className="copy-feedback-visual" aria-hidden="true">
          {state === "idle" && (variant === "icon" ? <span className="copy-icon" /> : <span className="copy-feedback-label">Copy</span>)}
          {state === "copying" && <span className="copy-feedback-spinner" />}
          {state === "copied" && <span className="copy-feedback-check">✓</span>}
          {state === "failed" && <span className="copy-feedback-failure">×</span>}
        </span>
      </button>
      {(state === "copying" || state === "copied") && <span className="sr-only" role="status" aria-live="polite">{label}</span>}
      {state === "failed" && <span className="sr-only" role="alert">{label}</span>}
    </Fragment>
  );
}
