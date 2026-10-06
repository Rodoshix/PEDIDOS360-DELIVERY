import { createContext, useContext } from 'react'
export const AdminAccessContext = createContext({ status: 'loading' })
export const useAdminAccess = () => useContext(AdminAccessContext)
