import { forwardRef, useId, type InputHTMLAttributes, type ReactNode, type TextareaHTMLAttributes } from 'react'

import { cn } from '../../lib/cn'

const CONTROL =
  'w-full rounded-[var(--radius-control)] border border-border bg-ivory-raised px-2.5 text-[13px] text-espresso ' +
  'placeholder:text-grey/80 transition-colors duration-[120ms] hover:border-taupe ' +
  'focus:border-burgundy focus:outline-none focus-visible:outline-none focus:ring-1 focus:ring-burgundy ' +
  'disabled:cursor-not-allowed disabled:opacity-55 aria-[invalid=true]:border-loss'

/**
 * Label above, control, then helper or error below (never a placeholder used as a label). Wires up ids,
 * aria-describedby and aria-invalid for the control passed as a render function.
 */
export function Field({ label, helper, error, children }: {
  label: string
  helper?: string
  error?: string
  children: (control: { id: string; 'aria-describedby'?: string; 'aria-invalid'?: boolean }) => ReactNode
}) {
  const id = useId()
  const hintId = `${id}-hint`
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-[12px] font-medium text-espresso">
        {label}
      </label>
      {children({ id, 'aria-describedby': error || helper ? hintId : undefined, 'aria-invalid': error ? true : undefined })}
      {error ? (
        <p id={hintId} className="text-[12px] text-loss" role="alert">
          {error}
        </p>
      ) : helper ? (
        <p id={hintId} className="type-meta">
          {helper}
        </p>
      ) : null}
    </div>
  )
}

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(function Input(
  { className, ...props },
  ref,
) {
  return <input ref={ref} className={cn(CONTROL, 'h-8', className)} {...props} />
})

/** Right-aligned tabular input for amounts, prices and quantities. */
export const NumberInput = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(function NumberInput(
  { className, ...props },
  ref,
) {
  return <input ref={ref} inputMode="decimal" className={cn(CONTROL, 'tabular h-8 text-right', className)} {...props} />
})

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement>>(function Textarea(
  { className, ...props },
  ref,
) {
  return <textarea ref={ref} className={cn(CONTROL, 'min-h-20 py-2 leading-5', className)} {...props} />
})
