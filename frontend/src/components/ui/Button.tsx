import { forwardRef, type ButtonHTMLAttributes } from 'react'
import { Link, type LinkProps } from 'react-router'

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

function buttonClass(variant: Variant, size: Size, className?: string): string {
  return cn(
    'inline-flex items-center justify-center gap-1.5 whitespace-nowrap rounded-[var(--radius-control)] font-medium',
    'transition-colors duration-[120ms] disabled:cursor-not-allowed disabled:opacity-45',
    VARIANTS[variant],
    SIZES[size],
    className,
  )
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
      className={buttonClass(variant, size, className)}
      {...props}
    />
  )
})

/** A navigation link styled as a button (a real <a>, not a button inside a link). */
export function ButtonLink({ variant = 'secondary', size = 'md', className, ...props }: LinkProps & { variant?: Variant; size?: Size }) {
  return <Link className={buttonClass(variant, size, className)} {...props} />
}
