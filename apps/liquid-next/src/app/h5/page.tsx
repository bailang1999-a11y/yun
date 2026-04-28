import { CategoryProvider } from "@/lib/catalog";
import { LiquidDrawerNavigator } from "@/components/LiquidDrawerNavigator";

export default function H5Demo() {
  return (
    <CategoryProvider>
      <LiquidDrawerNavigator />
    </CategoryProvider>
  );
}
