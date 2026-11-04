import type { NextConfig } from "next";

// "standalone" bundles only the files the server needs, so the production
// image is a Node runtime plus one folder rather than the whole node_modules.
const config: NextConfig = {
  output: "standalone",
};

export default config;
