import { useState } from "react";
import { useForm } from "react-hook-form";
import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { DateInput } from "./DateInput";
import { FormField } from "./FormField";

function Controlled({ onValue }: { onValue: (v: string) => void }) {
  const [value, setValue] = useState("");
  return (
    <FormField label="Début">
      <DateInput value={value} onChange={(e) => { setValue(e.target.value); onValue(e.target.value); }} />
    </FormField>
  );
}

function Registered({ onSubmit }: { onSubmit: (v: { day: string }) => void }) {
  const form = useForm({ defaultValues: { day: "" } });
  return (
    <form onSubmit={form.handleSubmit(onSubmit)}>
      <FormField label="Jour"><DateInput {...form.register("day")} /></FormField>
      <button type="submit">Valider</button>
    </form>
  );
}

/** Le calendrier natif est un champ date caché, voisin du champ texte. */
const pick = (label: string, iso: string) => {
  const calendar = screen.getByLabelText(label).parentElement!.querySelector<HTMLInputElement>('input[type="date"]')!;
  fireEvent.change(calendar, { target: { value: iso } });
};

describe("DateInput", () => {
  it("saisie au clavier ou choix dans le calendrier, au format JJ/MM/AAAA", async () => {
    const values: string[] = [];
    render(<Controlled onValue={(v) => values.push(v)} />);
    const field = screen.getByLabelText("Début");
    expect(field).toHaveAttribute("placeholder", "JJ/MM/AAAA");
    expect(screen.getByRole("button", { name: "Choisir dans le calendrier" })).toBeInTheDocument();

    await userEvent.type(field, "01/02/2027");
    expect(field).toHaveValue("01/02/2027");

    pick("Début", "2027-03-15");
    expect(field).toHaveValue("15/03/2027");
    expect(values.at(-1)).toBe("15/03/2027");
  });

  it("fonctionne avec register() de react-hook-form", async () => {
    const submitted = vi.fn();
    render(<Registered onSubmit={submitted} />);
    pick("Jour", "2026-12-24");
    await userEvent.click(screen.getByRole("button", { name: "Valider" }));
    expect(submitted.mock.calls[0][0]).toEqual({ day: "24/12/2026" });
  });
});
