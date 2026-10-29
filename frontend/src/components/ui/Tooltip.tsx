import * as RadixTooltip from '@radix-ui/react-tooltip'
import type { ReactNode } from 'react'

/** Wrap the app once; tooltips then open after a short, consistent delay. */
export function TooltipProvider({ children }: { children: ReactNode }) {
  return <RadixTooltip.Provider delayDuration={250}>{children}</RadixTooltip.Provider>
}

export function Tooltip({ content, children }: { content: ReactNode; children: ReactNode }) {
  return (
    <RadixTooltip.Root>
      <RadixTooltip.Trigger asChild>{children}</RadixTooltip.Trigger>
      <RadixTooltip.Portal>
        <RadixTooltip.Content
          sideOffset={6}
          className="z-50 max-w-[280px] rounded-[var(--radius-control)] border border-border bg-ivory-raised px-2.5 py-1.5 text-[12px] leading-4 text-espresso shadow-[var(--shadow-float)]"
        >
          {content}
        </RadixTooltip.Content>
      </RadixTooltip.Portal>
    </RadixTooltip.Root>
  )
}
