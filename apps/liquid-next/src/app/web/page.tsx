import { CategoryProvider } from "@/lib/catalog";
import { SpatialMillerColumns } from "@/components/SpatialMillerColumns";

export default function WebDemo() {
  return (
    <CategoryProvider>
      <SpatialMillerColumns />
    </CategoryProvider>
  );
}
