import * as RadixTabs from '@radix-ui/react-tabs'
import type { ReactNode } from 'react'

/** Text tabs over a hairline; the active tab is marked by a burgundy underline, not a filled pill. */
export function Tabs({ value, onValueChange, tabs, children }: {
  value: string
  onValueChange: (value: string) => void
  tabs: { value: string; label: string }[]
  children: ReactNode
}) {
  return (
    <RadixTabs.Root value={value} onValueChange={onValueChange}>
      <RadixTabs.List className="flex gap-6 border-b border-border">
        {tabs.map((t) => (
          <RadixTabs.Trigger
            key={t.value}
            value={t.value}
            className="-mb-px border-b-2 border-transparent pb-2 text-[13px] text-grey transition-colors duration-[120ms] hover:text-espresso data-[state=active]:border-burgundy data-[state=active]:text-espresso"
          >
            {t.label}
          </RadixTabs.Trigger>
        ))}
      </RadixTabs.List>
      {children}
    </RadixTabs.Root>
  )
}

export const TabPanel = ({ value, children }: { value: string; children: ReactNode }) => (
  <RadixTabs.Content value={value} className="pt-5 focus:outline-none">
    {children}
  </RadixTabs.Content>
)
