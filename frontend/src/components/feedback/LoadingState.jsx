import { Skeleton } from './Feedback.jsx'

export default function LoadingState({ label, compact = false }) {
  return <div className={`loading-state ${compact ? 'loading-state--compact' : ''}`}>
    <p role="status">{label}</p>
    <div aria-hidden="true" className="loading-state__shapes"><Skeleton /><Skeleton /><Skeleton /></div>
  </div>
}
