import * as React from 'react'
import { cn } from '@/lib/utils'

export interface SelectProps extends Omit<React.SelectHTMLAttributes<HTMLSelectElement>, 'onChange'> {
  onChange?: (event: { target: { value: string } }) => void
  placeholder?: string
}

// Native selection inherits fieldset locks, supports keyboard input, and fits small screens.
const Select = React.forwardRef<HTMLSelectElement, SelectProps>(
  ({ className, children, placeholder, ...props }, ref) => <select ref={ref} className={cn(
    'flex h-10 w-full min-w-0 rounded-md border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50',
    className,
  )} {...props}>
    {placeholder && <option value="" disabled>{placeholder}</option>}
    {children}
  </select>,
)
Select.displayName = 'Select'
export { Select }
