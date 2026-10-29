import { forwardRef, type ButtonHTMLAttributes } from 'react'

import { cn } from '../../lib/cn'

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger'
type Size = 'md' | 'sm'

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant
  size?: Size
}

const VARIANTS: Record<Variant, string> = {
  // The one burgundy control on a screen: the primary action.
  primary: 'bg-burgundy text-ivory border border-burgundy hover:bg-[#5a2532] active:bg-[#4d1f2b]',
  secondary: 'bg-transparent text-espresso border border-border hover:border-taupe hover:bg-ivory-raised',
  ghost: 'bg-transparent text-espresso border border-transparent hover:bg-ivory-raised',
  danger: 'bg-transparent text-loss border border-border hover:border-loss',
}

const SIZES: Record<Size, string> = {
  md: 'h-8 px-3.5 text-[13px]',
  sm: 'h-7 px-2.5 text-[12px]',
}

/** Square-cornered (2px), single-line, never pill-shaped. Disabled buttons keep their shape and lose contrast. */
export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'secondary', size = 'md', className, type = 'button', ...props },
  ref,
) {
  return (
    <button
      ref={ref}
      type={type}
      className={cn(
        'inline-flex items-center justify-center gap-1.5 whitespace-nowrap rounded-[var(--radius-control)] font-medium',
        'transition-colors duration-[120ms] disabled:cursor-not-allowed disabled:opacity-45',
        VARIANTS[variant],
        SIZES[size],
        className,
      )}
      {...props}
    />
  )
})
