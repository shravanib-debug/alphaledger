import { CaretDown, Check } from '@phosphor-icons/react'
import * as RadixSelect from '@radix-ui/react-select'

export interface SelectOption {
  value: string
  label: string
}

/** Radix select styled like the other controls; keyboard and screen-reader behaviour come from Radix. */
export function Select({ id, value, onValueChange, options, placeholder, disabled, ...aria }: {
  id?: string
  value: string | undefined
  onValueChange: (value: string) => void
  options: SelectOption[]
  placeholder?: string
  disabled?: boolean
  'aria-describedby'?: string
  'aria-invalid'?: boolean
}) {
  return (
    <RadixSelect.Root value={value} onValueChange={onValueChange} disabled={disabled}>
      <RadixSelect.Trigger
        id={id}
        {...aria}
        className="flex h-8 w-full items-center justify-between gap-2 rounded-[var(--radius-control)] border border-border bg-ivory-raised px-2.5 text-left text-[13px] text-espresso hover:border-taupe focus:border-burgundy focus:outline-none focus:ring-1 focus:ring-burgundy data-[placeholder]:text-grey disabled:opacity-55 aria-[invalid=true]:border-loss"
      >
        <RadixSelect.Value placeholder={placeholder} />
        <RadixSelect.Icon>
          <CaretDown size={13} weight="regular" className="text-grey" />
        </RadixSelect.Icon>
      </RadixSelect.Trigger>
      <RadixSelect.Portal>
        <RadixSelect.Content
          position="popper"
          sideOffset={4}
          className="z-50 min-w-[var(--radix-select-trigger-width)] overflow-hidden rounded-[var(--radius-control)] border border-border bg-ivory-raised shadow-[var(--shadow-float)]"
        >
          <RadixSelect.Viewport className="p-1">
            {options.map((o) => (
              <RadixSelect.Item
                key={o.value}
                value={o.value}
                className="relative flex h-8 cursor-default select-none items-center rounded-[var(--radius-control)] pl-7 pr-2.5 text-[13px] outline-none data-[highlighted]:bg-burgundy-tint data-[highlighted]:text-burgundy"
              >
                <RadixSelect.ItemIndicator className="absolute left-2">
                  <Check size={13} />
                </RadixSelect.ItemIndicator>
                <RadixSelect.ItemText>{o.label}</RadixSelect.ItemText>
              </RadixSelect.Item>
            ))}
          </RadixSelect.Viewport>
        </RadixSelect.Content>
      </RadixSelect.Portal>
    </RadixSelect.Root>
  )
}
