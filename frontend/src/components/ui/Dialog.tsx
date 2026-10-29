import { X } from '@phosphor-icons/react'
import * as RadixDialog from '@radix-ui/react-dialog'
import type { ReactNode } from 'react'

/**
 * Modal for decisions that need a moment (cancel a trade, resolve a break, pay a liability). Title in serif, body,
 * then actions right-aligned. Focus is trapped and Escape closes, from Radix.
 */
export function Dialog({ open, onOpenChange, title, description, children, actions }: {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description?: string
  children?: ReactNode
  actions: ReactNode
}) {
  return (
    <RadixDialog.Root open={open} onOpenChange={onOpenChange}>
      <RadixDialog.Portal>
        <RadixDialog.Overlay className="fixed inset-0 z-40 bg-espresso/25" />
        <RadixDialog.Content className="fixed left-1/2 top-[18vh] z-50 w-[min(520px,calc(100vw-32px))] -translate-x-1/2 rounded-[var(--radius-panel)] border border-border bg-ivory p-6 shadow-[var(--shadow-float)] focus:outline-none">
          <div className="flex items-start justify-between gap-4">
            <RadixDialog.Title className="type-section">{title}</RadixDialog.Title>
            <RadixDialog.Close aria-label="Close" className="-mr-1 rounded-[var(--radius-control)] p-1 text-grey hover:text-espresso">
              <X size={16} />
            </RadixDialog.Close>
          </div>
          {description ? (
            <RadixDialog.Description className="mt-1.5 text-[13px] text-grey">{description}</RadixDialog.Description>
          ) : (
            <RadixDialog.Description className="sr-only">{title}</RadixDialog.Description>
          )}
          {children && <div className="mt-5">{children}</div>}
          <div className="mt-6 flex justify-end gap-2 border-t border-border pt-4">{actions}</div>
        </RadixDialog.Content>
      </RadixDialog.Portal>
    </RadixDialog.Root>
  )
}
