type Props = {
  title: string;
  description?: string;
};

/** Page en attente d'implémentation (modules livrés phase par phase). */
export function PlaceholderPage({ title, description }: Props) {
  return (
    <>
      <div>
        <h1 className="m-0 font-display text-display">{title}</h1>
        {description && <p className="mt-1 text-muted-foreground">{description}</p>}
      </div>
      <section className="rounded-lg border border-border-subtle bg-card p-6 text-muted-foreground shadow-1">
        Ce module sera disponible dans une prochaine version.
      </section>
    </>
  );
}
