import Link from "next/link";
import { MonitorSmartphone, Smartphone } from "lucide-react";
import { ResponsiveContainer } from "@/components/ResponsiveContainer";

export default function Home() {
  return (
    <>
      <nav className="fixed right-4 top-4 z-50 flex gap-2">
        <Link className="glass-bubble flex items-center gap-2 px-3 py-2 text-xs text-white/75" href="/web">
          <MonitorSmartphone size={14} /> Web
        </Link>
        <Link className="glass-bubble flex items-center gap-2 px-3 py-2 text-xs text-white/75" href="/h5">
          <Smartphone size={14} /> H5
        </Link>
      </nav>
      <ResponsiveContainer />
    </>
  );
}
