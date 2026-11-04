// Shown by Next.js while a page's data is being fetched, so a slow first load
// reads as "working" rather than "hung".
export default function Loading() {
  return (
    <p className="panel muted" role="status">
      Loading prices… the first visit after a quiet period can take a few seconds.
    </p>
  );
}
