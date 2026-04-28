"use client";

import { CategoryProvider } from "@/lib/catalog";
import { LiquidDrawerNavigator } from "./LiquidDrawerNavigator";
import { SpatialMillerColumns } from "./SpatialMillerColumns";

export function ResponsiveContainer() {
  return (
    <CategoryProvider>
      <SpatialMillerColumns />
      <LiquidDrawerNavigator />
    </CategoryProvider>
  );
}
