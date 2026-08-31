# Stage 1: Build the TypeScript application
FROM node:20-alpine AS builder
WORKDIR /app

# Copy package files and install all dependencies (including devDependencies for building)
COPY package*.json ./
RUN npm install

# Copy source code and build
COPY . .
RUN npm run build

# Stage 2: Production runtime image
FROM node:20-alpine
WORKDIR /app

# Copy package files and install ONLY production dependencies
COPY package*.json ./
RUN npm ci --only=production

# Copy built JavaScript files from the builder stage
COPY --from=builder /app/dist ./dist

# Expose the gateway port
EXPOSE 3000

# Start the compiled Node server
CMD ["node", "dist/server.js"]