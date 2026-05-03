<?php

/**
 * A simple e-commerce cart system
 * Demonstrates OOP, type declarations, and modern PHP features
 */

declare(strict_types=1);

// Product class with readonly properties (PHP 8.2+)
class Product {
    public function __construct(
        public readonly int $id,
        public readonly string $name,
        private float $price,
        private int $stock = 0
    ) {}

    public function getPrice(): float {
        return $this->price;
    }

    public function getStock(): int {
        return $this->stock;
    }

    public function reduceStock(int $quantity): bool {
        if ($this->stock < $quantity) {
            return false;
        }
        $this->stock -= $quantity;
        return true;
    }

    public function increaseStock(int $quantity): void {
        $this->stock += $quantity;
    }
}

// CartItem class using constructor property promotion
class CartItem {
    public function __construct(
        public Product $product,
        public int $quantity = 1
    ) {}

    public function getSubtotal(): float {
        return $this->product->getPrice() * $this->quantity;
    }

    public function increment(): void {
        $this->quantity++;
    }

    public function decrement(): bool {
        if ($this->quantity <= 1) {
            return false;
        }
        $this->quantity--;
        return true;
    }
}

// Shopping Cart with array methods
class ShoppingCart {
    /** @var CartItem[] */
    private array $items = [];
    private ?float $discount = null;

    public function addProduct(Product $product, int $quantity = 1): bool {
        if (!$product->reduceStock($quantity)) {
            return false;
        }

        $itemId = $product->id;
        
        if (isset($this->items[$itemId])) {
            $this->items[$itemId]->quantity += $quantity;
        } else {
            $this->items[$itemId] = new CartItem($product, $quantity);
        }
        
        return true;
    }

    public function removeProduct(int $productId): bool {
        if (!isset($this->items[$productId])) {
            return false;
        }
        
        $item = $this->items[$productId];
        $item->product->increaseStock($item->quantity);
        unset($this->items[$productId]);
        
        return true;
    }

    public function updateQuantity(int $productId, int $quantity): bool {
        if (!isset($this->items[$productId])) {
            return false;
        }
        
        $item = $this->items[$productId];
        $currentQty = $item->quantity;
        
        if ($quantity > $currentQty) {
            $additional = $quantity - $currentQty;
            if (!$item->product->reduceStock($additional)) {
                return false;
            }
        } else if ($quantity < $currentQty) {
            $return = $currentQty - $quantity;
            $item->product->increaseStock($return);
        }
        
        if ($quantity <= 0) {
            unset($this->items[$productId]);
        } else {
            $item->quantity = $quantity;
        }
        
        return true;
    }

    public function getSubtotal(): float {
        return array_reduce(
            $this->items,
            fn($sum, CartItem $item) => $sum + $item->getSubtotal(),
            0.0
        );
    }

    public function setDiscount(float $percent): void {
        $this->discount = min(max($percent, 0), 100);
    }

    public function getTotal(): float {
        $total = $this->getSubtotal();
        
        if ($this->discount !== null) {
            $total *= (100 - $this->discount) / 100;
        }
        
        return round($total, 2);
    }

    /** @return CartItem[] */
    public function getItems(): array {
        return $this->items;
    }

    public function isEmpty(): bool {
        return empty($this->items);
    }

    public function clear(): void {
        foreach ($this->items as $item) {
            $item->product->increaseStock($item->quantity);
        }
        $this->items = [];
        $this->discount = null;
    }
}

// Order class with enum support (PHP 8.1+)
enum OrderStatus: string {
    case PENDING = 'pending';
    case PROCESSING = 'processing';
    case COMPLETED = 'completed';
    case CANCELLED = 'cancelled';
}

class Order {
    private static int $nextId = 1;
    
    public function __construct(
        public readonly int $id,
        public readonly ShoppingCart $cart,
        public OrderStatus $status = OrderStatus::PENDING,
        public readonly DateTime $createdAt = new DateTime()
    ) {}

    public static function createFromCart(ShoppingCart $cart): self {
        return new self(self::$nextId++, clone $cart);
    }

    public function getSummary(): array {
        return [
            'order_id' => $this->id,
            'status' => $this->status->value,
            'items' => array_map(
                fn(CartItem $item) => [
                    'product' => $item->product->name,
                    'quantity' => $item->quantity,
                    'price' => $item->product->getPrice(),
                    'subtotal' => $item->getSubtotal()
                ],
                $this->cart->getItems()
            ),
            'total' => $this->cart->getTotal(),
            'created_at' => $this->createdAt->format('Y-m-d H:i:s')
        ];
    }

    public function process(): void {
        if ($this->status === OrderStatus::PENDING) {
            $this->status = OrderStatus::PROCESSING;
        }
    }

    public function complete(): void {
        if ($this->status === OrderStatus::PROCESSING) {
            $this->status = OrderStatus::COMPLETED;
        }
    }

    public function cancel(): void {
        if ($this->status !== OrderStatus::COMPLETED) {
            $this->status = OrderStatus::CANCELLED;
            // Restore stock
            foreach ($this->cart->getItems() as $item) {
                $item->product->increaseStock($item->quantity);
            }
        }
    }
}

// ============ USAGE EXAMPLE ============

// Create products
$products = [
    new Product(1, "Laptop", 999.99, 10),
    new Product(2, "Mouse", 29.99, 50),
    new Product(3, "Keyboard", 79.99, 30),
    new Product(4, "Monitor", 299.99, 15),
];

// Create shopping cart
$cart = new ShoppingCart();

// Add items
$cart->addProduct($products[0]);  // Laptop x1
$cart->addProduct($products[1], 2); // Mouse x2
$cart->addProduct($products[2], 1); // Keyboard x1

// Apply 10% discount
$cart->setDiscount(10);

// Display cart
echo "=== SHOPPING CART ===\n";
foreach ($cart->getItems() as $item) {
    printf(
        "%s x%d = $%.2f\n",
        $item->product->name,
        $item->quantity,
        $item->getSubtotal()
    );
}
printf("\nSubtotal: $%.2f\n", $cart->getSubtotal());
printf("Discount: 10%%\n");
printf("Total: $%.2f\n", $cart->getTotal());

// Create order
$order = Order::createFromCart($cart);
$order->process();
$order->complete();

echo "\n=== ORDER SUMMARY ===\n";
print_r($order->getSummary());

// Check remaining stock
echo "\n=== REMAINING STOCK ===\n";
foreach ($products as $product) {
    printf("%s: %d units\n", $product->name, $product->getStock());
}

// Clean up
$cart->clear();