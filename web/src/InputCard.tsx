import { useEffect, useMemo, useRef, useState } from "react";
import { formatAge } from "./dashboard";
import { useSharedClock } from "./clock";
import type { InputField, InputRequest } from "./protocol";

export function inputAttentionLabel(input: InputRequest): string {
  return input.supported ? "Waiting for user input" : "Waiting for unsupported user input";
}

function initialValues(input: InputRequest): Record<string, unknown> {
  return Object.fromEntries(input.fields.flatMap((field) =>
    field.default !== undefined ? [[field.id, field.default]] : []
  ));
}

export function InputCard({
  input,
  focused = false,
  connected,
  onRespond,
}: {
  input: InputRequest;
  focused?: boolean;
  connected: boolean;
  onRespond: (inputId: string, response: Record<string, unknown>) => Promise<void>;
}) {
  const now = useSharedClock();
  const [values, setValues] = useState<Record<string, unknown>>(() => initialValues(input));
  const [other, setOther] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const formRef = useRef<HTMLFormElement>(null);
  const rootRef = useRef<HTMLElement>(null);
  const disabled = !connected || submitting || input.status !== "pending";
  const status = submitting || input.status === "submitting"
    ? "Submitting response…"
    : input.status === "resolved"
      ? input.resolution === "resolvedElsewhere" ? "Already resolved in another client." : "Input resolved."
      : input.status === "expired" ? "This input request is no longer available." : null;
  const normalizedValues = useMemo(() => Object.fromEntries(input.fields.flatMap((field) => {
    const value = values[field.id];
    if (value === "__other__") return [[field.id, other[field.id] ?? ""]];
    return value === undefined || value === null || value === "" && !field.required ? [] : [[field.id, value]];
  })), [input.fields, other, values]);

  useEffect(() => {
    if (!focused) return;
    const frame = requestAnimationFrame(() => {
      rootRef.current?.scrollIntoView({ block: "center", behavior: "smooth" });
      rootRef.current?.focus({ preventScroll: true });
    });
    return () => cancelAnimationFrame(frame);
  }, [focused]);

  const respond = async (response: Record<string, unknown>) => {
    if (disabled) return;
    setSubmitting(true);
    setError("");
    try {
      await onRespond(input.id, response);
    } catch (caught) {
      const message = caught instanceof Error ? caught.message : "Input response failed";
      setError(/already resolved/i.test(message) ? "Already resolved in another client." : message);
      setSubmitting(false);
    }
  };

  return <article ref={rootRef} tabIndex={-1} className={`approval-card input-card ${focused ? "approval-focused" : ""} ${input.supported ? "" : "input-unsupported"}`} id={`input-${input.id}`}>
    <header><div><span className="approval-kicker">{input.source === "mcp" ? `Requested by ${input.serverName ?? "MCP server"}` : "Codex needs input"}</span><h2>{input.title}</h2></div><time>{formatAge(input.createdAt, now)}</time></header>
    {input.message && <p className="approval-reason">{input.message}</p>}
    {!input.supported && <p className="unsupported-request">{input.unsupportedMessage} {input.canDecline || input.canCancel ? "You can decline or dismiss it safely." : "Open another compatible Codex client to answer it."}</p>}
    {input.supported && <form ref={formRef} className="input-fields" onSubmit={(event) => {
      event.preventDefault();
      if (!formRef.current?.reportValidity()) return;
      const invalid = input.fields.find((field) => field.type === "multipleChoice" && (
        ((normalizedValues[field.id] as string[] | undefined)?.length ?? 0) < (field.minSelections ?? 0)
        || ((normalizedValues[field.id] as string[] | undefined)?.length ?? 0) > (field.maxSelections ?? field.options?.length ?? 0)
      ));
      if (invalid) { setError(`${invalid.label} has an invalid number of selections.`); return; }
      void respond({ action: "accept", values: normalizedValues });
    }}>
      {input.fields.map((field) => <InputControl key={field.id} field={field} groupName={`${input.id}-${field.id}`} value={values[field.id]} other={other[field.id] ?? ""} disabled={disabled} onChange={(value) => setValues((previous) => ({ ...previous, [field.id]: value }))} onOther={(value) => setOther((previous) => ({ ...previous, [field.id]: value }))} />)}
      <div className="approval-actions"><button className="approval-primary" disabled={disabled}>Submit</button></div>
    </form>}
    {status && <p className="approval-status" role="status">{status}</p>}
    {error && <p className="approval-error" role="alert">{error}</p>}
    {(input.canDecline || input.canCancel) && <div className="approval-actions input-secondary-actions">
      {input.canDecline && <button type="button" className="approval-danger" disabled={disabled} onClick={() => void respond({ action: "decline" })}>Decline</button>}
      {input.canCancel && <button type="button" disabled={disabled} onClick={() => void respond({ action: "cancel" })}>Cancel</button>}
    </div>}
  </article>;
}

function InputControl({ field, groupName, value, other, disabled, onChange, onOther }: {
  field: InputField;
  groupName: string;
  value: unknown;
  other: string;
  disabled: boolean;
  onChange: (value: unknown) => void;
  onOther: (value: string) => void;
}) {
  const help = <>{field.description && <small>{field.description}</small>}{!field.required && <small>Optional</small>}</>;
  if (field.type === "shortText" || field.type === "longText") {
    const common = { value: typeof value === "string" ? value : "", minLength: field.minLength, maxLength: field.maxLength, required: field.required, disabled, onChange: (event: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => onChange(event.target.value) };
    return <label className="input-field"><strong>{field.label}</strong>{help}{field.type === "longText" ? <textarea {...common} rows={5} /> : <input {...common} type={field.secret ? "password" : "text"} autoComplete="off" />}</label>;
  }
  if (field.type === "singleChoice") return <fieldset className="input-field" disabled={disabled}><legend>{field.label}</legend>{help}{field.options?.map((option) => <label key={option.value}><input type="radio" name={groupName} value={option.value} checked={value === option.value} required={field.required} onChange={() => onChange(option.value)} /><span><strong>{option.label}</strong>{option.description && <small>{option.description}</small>}</span></label>)}{field.allowOther && <label><input type="radio" name={groupName} value="__other__" checked={value === "__other__"} required={field.required} onChange={() => onChange("__other__")} /><span>Other</span></label>}{field.allowOther && value === "__other__" && <input aria-label={`${field.label} other response`} value={other} required maxLength={field.maxLength} onChange={(event) => onOther(event.target.value)} />}</fieldset>;
  if (field.type === "multipleChoice") {
    const selected = Array.isArray(value) ? value as string[] : [];
    return <fieldset className="input-field" disabled={disabled}><legend>{field.label}</legend>{help}<small>Select {field.minSelections ?? 0} to {field.maxSelections ?? field.options?.length ?? 0}.</small>{field.options?.map((option) => <label key={option.value}><input type="checkbox" value={option.value} checked={selected.includes(option.value)} onChange={(event) => onChange(event.target.checked ? [...selected, option.value] : selected.filter((item) => item !== option.value))} /><span><strong>{option.label}</strong>{option.description && <small>{option.description}</small>}</span></label>)}</fieldset>;
  }
  return <fieldset className="input-field" disabled={disabled}><legend>{field.label}</legend>{help}{[true, false].map((choice) => <label key={String(choice)}><input type="radio" name={groupName} checked={value === choice} required={field.required} onChange={() => onChange(choice)} /><span>{choice ? field.type === "confirmation" ? "Confirm" : "Yes" : field.type === "confirmation" ? "Do not confirm" : "No"}</span></label>)}</fieldset>;
}
