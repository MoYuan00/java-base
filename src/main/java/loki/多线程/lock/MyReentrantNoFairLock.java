package loki.多线程.lock;

import sun.misc.Unsafe;

import java.lang.reflect.Field;

/**
 * 实现锁
 * 1. 实现队列
 * 2. 锁状态变量
 * 3. 等待队列
 * 4. 获取锁的线程
 */
public class MyReentrantNoFairLock {

    /**
     * 锁状态
     */
    private volatile int state;

    /**
     * 当前持有锁的线程
     */
    private volatile Thread lockThread;

    /**
     * unsafe
     */
    private static final Unsafe unsafe = getUnsafe();

    private static Unsafe getUnsafe() {
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            return (Unsafe) theUnsafe.get(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    private static long getStateOffset() {
        try {
            Field state = MyReentrantNoFairLock.class.getDeclaredField("state");
            return unsafe.objectFieldOffset(state);
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    /**
     * state的偏移量
     */
    private static final long stateOffset = getStateOffset();


    /**
     * 更新状态
     */
    private boolean casState(int except, int update) {
        return unsafe.compareAndSwapInt(this, stateOffset, except, update);
    }

    private final SyncLinkedList list = new SyncLinkedList();

    private static class SyncLinkedList {
        /**
         * 链表头节点
         */
        private volatile Node<Thread> head = new Node<>();
        /**
         * 链表尾
         */
        private volatile Node<Thread> tail = head;

        /**
         * 更新状态
         */
        private boolean casTail(Node<Thread> except, Node<Thread> update) {
            return unsafe.compareAndSwapObject(this, tailOffset, except, update);
        }

        /**
         * tail的偏移量
         */
        private static final long tailOffset = getTailOffset();

        private static long getTailOffset() {
            try {
                Field state = SyncLinkedList.class.getDeclaredField("tail");
                return unsafe.objectFieldOffset(state);
            } catch (NoSuchFieldException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }

        public Node<Thread> enqueueCurrentThread() {
            Thread thread = Thread.currentThread();
            while (true) {
                Node<Thread> t = this.tail;
                Node<Thread> node = Node.build(thread, t);
                if (casTail(t, node)) {
                    // 更新尾节点成功了，让原尾节点的next指针指向当前节点 -- 这里访问同一个 t 只会有一个线程
                    // cas成功代表 tail的下一个节点 一定是 node
                    t.next = node;
                    return node;
                }
            }
        }

        public void setHead(Node<Thread> newHead) {
            head.node = null;
            head.next = null;
            head = newHead;
        }

        public Node<Thread> getHeadNode() {
            return head.next;
        }
    }

    /**
     * 阻塞链表节点
     */
    private static class Node<T> {
        private Node<T> pre;
        private Node<T> next;
        private T node;

        public static <T> Node<T> build(T node, Node<T> pre) {
            Node<T> tNode = new Node<>();
            tNode.node = node;
            tNode.pre = pre;
            return tNode;
        }
    }


    public void lock() {
        // 尝试更新state字段，更新成功说明占有了锁
        Thread thread = Thread.currentThread();
        try {
            if (lockThread == thread && state > 0 && casState(state, state + 1)) {
                return;
            } else if (casState(0, 1)) {
                // 一个线程
                return;
            }

            // 占有失败 -- 会有多个线程
            // 未更新成功则入队
            Node<Thread> node = list.enqueueCurrentThread();
            Node<Thread> pre = node.pre;
            for (; ; ) {
                // 再次尝试获取锁，需要检测上一个节点是不是head，按入队顺序加锁
                if (node.pre != list.head || !casState(0, 1)) {
                    unsafe.park(false, 0L);
                } else {
                    // 下面不需要原子更新，因为同时只有一个线程访问到这里

                    // head后移一位
                    list.setHead(node);
                    // 将上一个节点从链表中剔除，协助GC
                    pre.next = null;
                    return;
                }
            }
        } finally {
            System.out.println("start: " + Thread.currentThread().getName());
            lockThread = thread;
        }
    }

    // 解锁
    public void unlock() {
        System.out.println("end: " + Thread.currentThread().getName());
        // 把state更新成0，这里不需要原子更新，因为同时只有一个线程访问到这里
        casState(state, state - 1);
        if (state == 0) {
            lockThread = null;
            // 下一个待唤醒的节点
            Node<Thread> node = list.getHeadNode();
            // 下一个节点不为空，就唤醒它
            if (node != null) {
                unsafe.unpark(node.node);
            }
        }
    }

}
